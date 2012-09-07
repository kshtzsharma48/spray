/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.io

import java.net.InetSocketAddress
import akka.actor.{ActorRef, Status, Props, Actor}
import pipelining._


trait ConnectionActors extends IOPeer { ioPeer =>

  override protected def createConnectionHandle(theKey: Key, theAddress: InetSocketAddress, theCommander: ActorRef) = {
    new Handle {
      val key = theKey
      val remoteAddress = theAddress
      val commander = theCommander
      val handler = context.actorOf(Props(createConnectionActor(this))) // must be initialized last
    }
  }

  protected def createConnectionActor(handle: Handle): IOConnectionActor = new IOConnectionActor(handle)

  protected def pipeline: PipelineStage

  class IOConnectionActor(val handle: Handle) extends Actor {
    protected val pipelines = pipeline.buildPipelines(
      context = PipelineContext(handle, context),
      commandPL = baseCommandPipeline,
      eventPL = baseEventPipeline
    )

    protected def baseCommandPipeline: Pipeline[Command] = {
      case IOPeer.Send(buffers, ack)          => ioBridge ! IOBridge.Send(handle, buffers, ack)
      case IOPeer.Close(reason)               => ioBridge ! IOBridge.Close(handle, reason)
      case IOPeer.StopReading                 => ioBridge ! IOBridge.StopReading(handle)
      case IOPeer.ResumeReading               => ioBridge ! IOBridge.ResumeReading(handle)
      case IOPeer.Tell(receiver, msg, sender) => receiver.tell(msg, sender)
      case _: Droppable => // don't warn
      case cmd => log.warning("commandPipeline: dropped {}", cmd)
    }

    protected def baseEventPipeline: Pipeline[Event] = {
      case x: IOPeer.Closed =>
        log.debug("Stopping connection actor, connection was closed due to {}", x.reason)
        context.stop(self)
        ioPeer.self ! x // inform our owner of our closing
      case _: Droppable => // don't warn
      case ev => log.warning("eventPipeline: dropped {}", ev)
    }

    protected def receive = {
      case x: Command => pipelines.commandPipeline(x)
      case x: Event => pipelines.eventPipeline(x)
      case Status.Failure(x: CommandException) => pipelines.eventPipeline(x)
    }
  }

}