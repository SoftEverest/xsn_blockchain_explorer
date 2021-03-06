package com.xsn.explorer.data.common

import java.sql.DriverManager
import com.whisk.docker.{DockerCommandExecutor, DockerContainer, DockerContainerState, DockerKit, DockerReadyChecker}

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext

trait DockerPostgresService extends DockerKit {

  import DockerPostgresService._

  import scala.concurrent.duration._

  override val PullImagesTimeout = 300.minutes
  override val StartContainersTimeout = 300.seconds
  override val StopContainersTimeout = 300.seconds

  val postgresContainer = DockerContainer(PostgresImage)
    .withCommand("-N 1000")
    .withPorts((PostgresAdvertisedPort, Some(PostgresExposedPort)))
    .withEnv(
      s"POSTGRES_USER=$PostgresUsername",
      s"POSTGRES_PASSWORD=$PostgresPassword"
    )
    .withReadyChecker(
      new PostgresReadyChecker().looped(15, 1.second)
    )

  abstract override def dockerContainers: List[DockerContainer] =
    postgresContainer :: super.dockerContainers
}

@nowarn
object DockerPostgresService {

  val PostgresImage = "postgres:12"
  val PostgresUsername = "postgres"
  val PostgresPassword = "postgres"
  val DatabaseName = "xsn_blockchain"

  def PostgresAdvertisedPort = 5432
  def PostgresExposedPort = 44444

  class PostgresReadyChecker extends DockerReadyChecker {

    override def apply(
        container: DockerContainerState
    )(implicit docker: DockerCommandExecutor, ec: ExecutionContext) = {

      container
        .getPorts()
        .map { ports =>
          try {
            Class.forName("org.postgresql.Driver")
            val url = s"jdbc:postgresql://${docker.host}:$PostgresExposedPort/"
            Option(
              DriverManager
                .getConnection(url, PostgresUsername, PostgresPassword)
            )
              .foreach { conn =>
                // NOTE: For some reason the result is always false
                conn.createStatement().execute(s"CREATE DATABASE $DatabaseName")
                conn.close()
              }

            true
          } catch {
            case _: Throwable =>
              false
          }
        }
    }
  }
}
