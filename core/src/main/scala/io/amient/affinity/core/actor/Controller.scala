/*
 * Copyright 2016 Michal Harish, michal.harish@gmail.com
 *
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

package io.amient.affinity.core.actor

import akka.AkkaException
import akka.actor.{Actor, InvalidActorNameException, Props, Terminated}
import akka.event.Logging
import akka.util.Timeout
import akka.pattern.ask
import io.amient.affinity.core.ack
import io.amient.affinity.core.cluster.Node
import io.amient.affinity.core.util.Reply

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.control.NonFatal

object Controller {

  final case class CreateContainer(group: String, partitions: List[Int], partitionProps: Props) extends Reply[Unit]

  final case class ContainerOnline(group: String)

  final case class CreateGateway(handlerProps: Props) extends Reply[Int]

  final case class GatewayCreated(httpPort: Int)

  final case class GracefulShutdown() extends Reply[Unit]

}

class Controller extends Actor {

  private val log = Logging.getLogger(context.system, this)

  private val conf = Node.Conf(context.system.settings.config)
  private val shutdownTimeout = conf.Affi.ShutdownTimeoutMs().toLong milliseconds

  import Controller._

  val system = context.system

  private var gatewayPromise: Promise[Int] = null

  private val containers = scala.collection.mutable.Map[String, Promise[Unit]]()

  override def postStop(): Unit = {
    super.postStop()
  }

  import system.dispatcher

  implicit val scheduler = context.system.scheduler

  override def receive: Receive = {

    case request@CreateContainer(group, partitions, partitionProps) => sender.replyWith(request) {
      try {
        log.debug(s"Creating Container for $group with partitions $partitions")
        context.actorOf(Props(new Container(group) {
          for (partition <- partitions) {
            context.actorOf(partitionProps, name = partition.toString)
          }
        }), name = group)
        val promise = Promise[Unit]()
        containers.put(group, promise)
        promise.future
      } catch {
        case _: InvalidActorNameException => containers(group).future
        case NonFatal(e) =>
          log.error(e, s"Could not create container for $group with partitions $partitions")
          throw e
      }
    }

    case Terminated(child) if (containers.contains(child.path.name)) =>
      val promise = containers(child.path.name)
      if (!promise.isCompleted) promise.failure(new AkkaException("Container initialisation failed"))

    case ContainerOnline(group) => containers(group) match {
      case promise => if (!promise.isCompleted) containers(group).success(())
    }

    case request@CreateGateway(gatewayProps) => try {
      val gatewayRef = context.actorOf(gatewayProps, name = "gateway")
      context.watch(gatewayRef)
      gatewayPromise = Promise[Int]()
      sender.replyWith(request) {
        gatewayPromise.future
      }
      gatewayRef ! CreateGateway
    } catch {
      case _: InvalidActorNameException =>
        sender.replyWith(request) {
          gatewayPromise.future
        }
    }

    case Terminated(child) if (child.path.name == "gateway") =>
      if (!gatewayPromise.isCompleted) gatewayPromise.failure(new AkkaException("Gateway initialisation failed"))

    case GatewayCreated(httpPort) => if (!gatewayPromise.isCompleted) {
      log.info("Gateway online (with http)")
      gatewayPromise.success(httpPort)
    }

    case request@GracefulShutdown() => sender.replyWith(request) {
      implicit val timeout = Timeout(shutdownTimeout)
      Future.sequence(context.children map { child =>
        log.debug("Requesting GracefulShutdown from " + child)
        child ? GracefulShutdown() recover {
          case any =>
            log.warning(s"$child failed while executing GracefulShutdown request: ", any.getMessage)
            context.stop(child)
        }
      }) map (_ => system.terminate())
    }

    case anyOther => log.warning("Unknown controller message " + anyOther)
  }

}
