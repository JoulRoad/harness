/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.actionml.core.spark

import java.io.File
import java.net.URI

import com.actionml.core.jobs.JobStatuses.JobStatus
import com.actionml.core.jobs.{Cancellable, JobDescription, JobStatuses}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.apache.livy._
import org.apache.livy.scalaapi.{LivyScalaClient, ScalaJobHandle}
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.json4s.JValue
import org.json4s.jackson.JsonMethods._

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try
import scala.util.control.NonFatal
import scala.reflect.runtime.universe._

trait SparkJobServerSupport {
  def submit[T: TypeTag](initParams: String,
                         engineId: String,
                         job: (SparkContext, Seq[Broadcast[AnyRef]]) => T,
                         sharedObjects: Seq[AnyRef]): JobDescription
  def status(engineId: String): Iterable[JobDescription]
  def cancel(jobId: String): Future[Unit]
}

class SparkJobCancellable(jobId: String) extends Cancellable {
  override def cancel(): Future[Unit] = LivyJobServerSupport.cancel(jobId)
}

object LivyJobServerSupport extends SparkJobServerSupport with LazyLogging {
  private case class ContextId(initParams: String, engineId: String, jobId: String)
  private case class ContextApi(client: LivyScalaClient, handlers: List[ScalaJobHandle[_]])
  private val contexts = TrieMap.empty[ContextId, ContextApi]
  private val config = ConfigFactory.load()
  private val livyUrl = config.getString("spark.job-server-url")

  override def submit[T: TypeTag](initParams: String, engineId: String, job: (SparkContext, Seq[Broadcast[AnyRef]]) => T, sharedObjects: Seq[AnyRef]): JobDescription = {
    val jobDescription = JobDescription.create
    val id = ContextId(initParams = initParams, engineId = engineId, jobId = jobDescription.jobId)
    try {
      val ContextApi(client, handlers) =
        contexts.getOrElseUpdate(id, ContextApi(mkNewClient(initParams, engineId, jobDescription.jobId), List.empty))
      // assumes that jars are already available to livy (via livy.conf)
      val lib: Iterable[File] = new File("lib").listFiles
      Await.result(Future.sequence(lib.filter(f => f.isFile && f.getName.endsWith(".jar") && !(f.getName.startsWith("org.json4s.json4s")))
        .map(jar => client.uploadJar(jar))
      ), Duration.Inf)
      val handler = client.submit(jc => {
        import scala.reflect.runtime.universe._
        typeTag[Int]
        typeTag[Float]
        typeTag[Boolean]
        typeTag[T]
        job(jc.sc, sharedObjects.map(jc.sc.broadcast))
      })
      contexts.update(id, ContextApi(client, handler :: handlers))
      handler.onJobQueued(logger.info(s"Spark job $jobDescription queued"))
      handler.onJobStarted(logger.info(s"Spark job $jobDescription started"))
      handler.onJobCancelled(_ => logger.info(s"Spark job $jobDescription cancelled"))
      handler.onFailure { case throwable => logger.error(s"Spark job $jobDescription failed with error $throwable", throwable) }
      handler.onSuccess { case _ => logger.info(s"Spark job $jobDescription completed successfully") }
      jobDescription
    } catch {
      case NonFatal(e) =>
        logger.error("Jars upload problem", e)
        throw e
    }
  }

  override def status(engineId: String): Iterable[JobDescription] = {
    contexts.flatMap {
      case (ContextId(_, eId, jobId), api) if eId == engineId =>
        api.handlers.flatMap(handler => state2Status(handler.state).map(s => JobDescription(jobId, s)))
    }
  }

  override def cancel(jobId: String): Future[Unit] = Future.successful ()


  private def state2Status(state: JobHandle.State): Option[JobStatus] = {
    import JobHandle.State._
    if (state == QUEUED || state == SENT) Some(JobStatuses.queued)
    else if (state == STARTED) Some(JobStatuses.executing)
    else None
  }

  def mkNewClient(initParams: String, engineId: String, jobId: String): LivyScalaClient = {
    import scala.collection.JavaConversions._
    val configMap = configParams ++ parseAndValidate[Map[String, String]](initParams, transform = _ \ "sparkConf")
    val master = configMap.getOrElse("master", "local") // "spark://172.17.0.3:7077"
    val builder = new LivyClientBuilder()
      .setURI(new URI(livyUrl))
      .setAll(configMap - "master")
      .setConf("spark.master", master)
      .setConf("spark.app.name", jobId)
    new LivyScalaClient(builder.build)
  }

  private lazy val configParams: Map[String, String] = {
    import net.ceedubs.ficus.Ficus._
    Try {
      Map("spark.eventLog.dir" -> config.as[String]("spark.eventLog.dir"))
    }.getOrElse(Map.empty)
  }

  private def parseAndValidate[T](jsonStr: String, transform: JValue => JValue = a => a)(implicit mf: Manifest[T]): T = {
    implicit val _ = org.json4s.DefaultFormats
    transform(parse(jsonStr)).extract[T]
  }
}
