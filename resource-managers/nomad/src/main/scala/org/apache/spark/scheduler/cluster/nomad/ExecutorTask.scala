/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler.cluster.nomad

import com.hashicorp.nomad.apimodel.Task

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.internal.config.EXECUTOR_MEMORY
import org.apache.spark.util.Utils

private[spark] object ExecutorTask
  extends SparkNomadTaskType("executor", "executor", EXECUTOR_MEMORY) {

  val LOG_KEY_FOR_ALLOC_ID = "nomad_alloc_id"

  private val PROPERTIES_NOT_TO_FORWARD_TO_EXECUTOR = scala.collection.Set[String](
    "spark.driver.port",
    "spark.blockManager.port",
    "spark.ui.port",
    NomadClusterManagerConf.AUTH_TOKEN.key)

  private val executorPort = ConfigurablePort("executor")

  def configure(
      jobConf: SparkNomadJob.CommonConf,
      conf: SparkConf,
      task: Task,
      shuffleServicePortPlaceholder: Option[String]
  ): Task = {

    val blockManagerPort = ConfigurablePort("blockManager")

    super.configure(jobConf, conf, task, Seq(executorPort, blockManagerPort), "spark-class")

    conf.getExecutorEnv.foreach((task.addEnv _).tupled)

    val executorConf = {
      val explicitConf = Seq(
        "spark.executor.port" -> executorPort.placeholder,
        "spark.blockManager.port" -> blockManagerPort.placeholder
      ) ++ shuffleServicePortPlaceholder.map("spark.shuffle.service.port" -> _)

      val forwardedConf = conf.getAll
        .filter { case (name, _) =>
          (SparkConf.isExecutorStartupConf(name) || name == SecurityManager.SPARK_AUTH_SECRET_CONF
            ) && !PROPERTIES_NOT_TO_FORWARD_TO_EXECUTOR.contains(name)
        }

      (explicitConf ++ forwardedConf.toSeq).map {
        case (k, v) => s"-D$k=$v"
          .replaceAllLiterally("\\", "\\\\")
          .replaceAllLiterally("\"", "\\\"")
      }.map('"' + _ + '"')
    }

    val extraJavaOpts = conf.getOption("spark.executor.extraJavaOptions")
      .map(Utils.splitCommandString).getOrElse(Seq.empty)

    task.addEnv("SPARK_EXECUTOR_OPTS", (extraJavaOpts ++ executorConf).mkString(" "))

    task.addEnv("SPARK_EXECUTOR_MEMORY", jvmMemory(conf, task))

    val sparkLocalDirs = conf.getOption("spark.local.dir").getOrElse("/spark-local-dir")
    task.addEnv("SPARK_LOCAL_DIRS", sparkLocalDirs)

    // Have the executor give its allocation ID as its log URL
    // The driver will lookup the actual log URLs
    task.addEnv("SPARK_LOG_URL_" + LOG_KEY_FOR_ALLOC_ID, "${NOMAD_ALLOC_ID}")

    if (conf.getOption("spark.executor.extraClassPath").nonEmpty) {
      task.addEnv("SPARK_EXECUTOR_CLASSPATH", conf.getOption("spark.executor.extraClassPath").get)
    }

    task
  }

  def addDriverArguments(
      jobConf: SparkNomadJob.CommonConf,
      conf: SparkConf,
      task: Task,
      driverUrl: String
  ): Unit = {
    appendArguments(task,
      Seq(
        "org.apache.spark.executor.NomadExecutorBackend",
        "--hostname", executorPort.ipPlaceholder,
        "--app-id", jobConf.appId,
        "--cores", conf.getInt("spark.executor.cores", 1).toString,
        "--driver-url",
        driverUrl),
      idempotent = true
    )
  }

}
