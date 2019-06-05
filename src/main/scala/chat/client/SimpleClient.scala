package chat.client

import util.control.Breaks._
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, ActorSystem, FSM, PoisonPill, Props}
import chat._
import chat.client.SimpleClient.{Data, LeaveCmd, State}
import com.typesafe.config.ConfigFactory


object SimpleClient {
  def props(serverActorRef: ActorSelection): Props =
    Props(new SimpleClient(serverActorRef))

  // client internals
  sealed trait ClientMessage
  final case class InputLineMsg(line: String) extends ClientMessage

  sealed trait Command
  final case class JoinCmd(room: String) extends Command
  final case object GetRoomsCmd extends Command
  final case object LeaveCmd extends Command
  final case object UnknownCmd extends Command

  final case class ParsingError(private val message: String = "",
                                private val cause: Throwable = None.orNull)
    extends Exception(message, cause)


  // client states
  sealed trait State
  case object Connecting extends State
  case object Connected extends State
  case object Chatting extends State

  // client data
  sealed trait Data
  case object Uninitialized extends Data
  case class ConnectionData(nick: String) extends Data
  case class SessionData(nick: String, remoteRef: ActorRef) extends Data
}

class SimpleClient(serverActorRef: ActorSelection) extends Actor
  with ActorLogging
  with FSM[State, Data] {
  import SimpleClient._

  override def preStart(): Unit = log.info("Client starting.")
  override def postStop(): Unit = log.info("Client stopped.")

  startWith(Connecting, Uninitialized)

  when (Connecting) {
    case Event(InputLineMsg(line), Uninitialized) =>
      val nick = line.split(" ").head
      if (nick.forall(_.isLetter))
        serverActorRef ! Login(nick)
      else
        println("Nickname can contain letters only!")
      stay using Uninitialized

    case Event(LoggedIn(nick: String), Uninitialized) =>
      goto(Connected) using ConnectionData(nick)

    case Event(NameTaken(nick: String), Uninitialized) =>
      println(s"$nick is taken, choose another one!")
      stay using Uninitialized
  }

  when(Connected) {
    case Event(Joined(remoteActorRef), ConnectionData(nick)) =>
      goto(Chatting) using SessionData(nick, remoteActorRef)

    case Event(ChatRooms(rooms: List[String]), data: ConnectionData) =>
      println("CHAT ROOMS:")
      rooms.foreach(r => println(s"-> $r"))
      stay using data

    case Event(InputLineMsg(line), ConnectionData(nick)) =>
      try {
        parseLine(line) match {
          case Left(cmd) =>
            cmd match {
              case JoinCmd(room) =>
                serverActorRef ! Join(nick, room)
              case GetRoomsCmd =>
                serverActorRef ! GetChatRooms
              case UnknownCmd =>
                println(s"Unknown command: $cmd")
              case _ =>
                println(s"Command $cmd is not supported in this state.")
            }
          case Right(_) =>
            println("Only commands are supported in this state.")
        }
      } catch {
        case ParsingError(message, _) => println(s"Parsing error occurred: $message")
      }

      stay
  }

  when(Chatting) {
    case Event(ChatMessage(from, msg), data: SessionData) =>
      println(s">>> $from: $msg")
      stay using data

    case Event(ChatLog(log), data: SessionData) =>
      log.map({case ChatMessage(from, msg) => s">>> $from: $msg"}).foreach(println)
      stay using data

    case Event(Left(room), data: SessionData) =>
      println(s"Left $room")
      goto(Connected) using ConnectionData(data.nick)

    case Event(InputLineMsg(line), SessionData(nick, remoteRef)) =>
      try {
        parseLine(line) match {
          case Left(cmd) =>
            cmd match {
              case LeaveCmd => Leave(nick)
              case UnknownCmd => println(s"Unknown command: $cmd")
              case _ => println(s"Command $cmd is not supported in this state.")
            }
          case Right(msg) => remoteRef ! ChatMessage(nick, msg)
        }
      } catch {
        case ParsingError(message, _) => println(s"Parsing error occurred: $message")
      }

      stay
  }

  whenUnhandled {
    case Event(message, _) =>
      log.error(s"Unexpected message: $message")
      stay
  }

  def parseLine(line: String): Either[Command, String] = {
    val trimmed = line.trim()

    trimmed.split(" ", 2) match {
      case Array() =>
        Right("")
      case Array(head) =>
        if (head.take(1) == "\\")
          Left(parseCommand(head.drop(1), None))
        else
          Right(head)
      case Array(head, rem) =>
        if (head.take(1) == "\\")
          Left(parseCommand(head.drop(1), Some(rem)))
        else
          Right(trimmed)
    }
  }

  def parseCommand(command: String, rem: Option[String]): Command = {
    command match {
      case "rooms" =>
        if (rem.isDefined)
          throw ParsingError(message = "Command: \\rooms takes no parameters.")
        else
          GetRoomsCmd
      case "join" =>
        if (rem.isEmpty || rem.get.split(" ").length > 1)
          throw ParsingError(message = "Command: \\join takes exactly one parameter.")
        else
          JoinCmd(rem.get)
      case "leave" =>
        if (rem.isDefined)
          throw ParsingError(message = "Command: \\leave takes no parameters.")
        else
          LeaveCmd
      case _ =>
        UnknownCmd
    }
  }
}


object Main {
  def main(args: Array[String]): Unit = {
    val portConfig = ConfigFactory.load("client")
    val system = ActorSystem("chat-client-system", portConfig)

    val serverActorRef =
      system.actorSelection("akka.tcp://chat-server-system@127.0.0.1:2551/user/server")

    val clientActorRef = system.actorOf(SimpleClient.props(serverActorRef), "client")

    import SimpleClient._
    breakable {
      for (line <- scala.io.Source.stdin.getLines()) {
        line match {
          case "STOP" =>
            clientActorRef ! PoisonPill
          case _ =>
            clientActorRef ! InputLineMsg(line)
        }
      }
    }

    println("Done!")
    system.terminate()
  }
}
