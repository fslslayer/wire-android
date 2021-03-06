/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.messages.parts.footer

import android.content.Context
import com.waz.ZLog.ImplicitTag._
import com.waz.api.Message
import com.waz.api.Message.Status
import com.waz.model.MessageData
import com.waz.service.ZMessaging
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.CancellableFuture
import com.waz.utils._
import com.waz.utils.events.{ClockSignal, EventContext, Signal}
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.messages.MessageView.MsgBindOptions
import com.waz.zclient.messages.{LikesController, UsersController}
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.ZTimeFormatter
import com.waz.zclient.{Injectable, Injector, R}
import org.threeten.bp.{DateTimeUtils, Instant}

import scala.concurrent.duration._

/**
  * Be warned - the timestamp/footer logic and when to display what has more edges than a tetrahedron.
  */
class FooterViewController(implicit inj: Injector, context: Context, ec: EventContext) extends Injectable {
  import com.waz.threading.Threading.Implicits.Ui

  val zms                    = inject[Signal[ZMessaging]]
  val accents                = inject[AccentColorController]
  val selection              = inject[ConversationController].messages
  val signals                = inject[UsersController]
  val likesController        = inject[LikesController]
  val conversationController = inject[ConversationController]

  val opts            = Signal[MsgBindOptions]()
  val messageAndLikes = Signal[MessageAndLikes]()
  val isSelfMessage   = opts.map(_.isSelf)

  val message =
    for {
      id  <- messageAndLikes.map(_.message.id)
      msg <- zms.flatMap(_.messagesStorage.signal(id))
    } yield msg

  val isLiked     = messageAndLikes.map(_.likes.nonEmpty)
  val likedBySelf = messageAndLikes.map(_.likedBySelf)
  val expiring    = message.map { msg => msg.isEphemeral && !msg.expired && msg.expiryTime.isDefined }

  //if the user likes OR dislikes something, we want to allow the timestamp/footer to disappear immediately
  val likedBySelfTime = Signal(Instant.EPOCH)
  likedBySelf.onChanged(_ => likedBySelfTime ! Instant.now)

  val active =
    for {
      (activeId, time) <- selection.lastActive
      msgId            <- message.map(_.id)
      lTime            <- likedBySelfTime
      untilTimeout = Instant.now.until(time.plus(selection.ActivityTimeout)).asScala
      active <-
        if (msgId != activeId || lTime.isAfter(time) || untilTimeout <= Duration.Zero) Signal.const(false)
        else Signal.future(CancellableFuture.delayed(untilTimeout)(false)).orElse(Signal const true) // signal `true` switching to `false` on timeout
    } yield active

  val showTimestamp: Signal[Boolean] = for {
    liked     <- isLiked
    selfMsg   <- isSelfMessage
    expiring  <- expiring
    timeAct   <- active
  } yield
    timeAct || expiring || (selfMsg && !liked)

  val ephemeralTimeout: Signal[Option[FiniteDuration]] = message.map(_.expiryTime) flatMap {
    case None => Signal const None
    case Some(expiry) if expiry <= Instant.now => Signal const None
    case Some(expiry) =>
      ClockSignal(1.second) map { now =>
        Some(now.until(expiry).asScala).filterNot(_.isNegative)
      }
  }

  val conv = message.flatMap(signals.conv)

  val timestampText = for {
    selfUserId  <- signals.selfUserId
    convId      <- conv.map(_.id)
    isGroup     <- Signal.future(conversationController.isGroup(convId))
    msg         <- message
    timeout     <- ephemeralTimeout
  } yield {
    val timestamp = ZTimeFormatter.getSingleMessageTime(context, DateTimeUtils.toDate(msg.time))
    timeout match {
      case Some(t)                          => ephemeralTimeoutString(timestamp, t)
      case None if selfUserId == msg.userId => statusString(timestamp, msg, isGroup)
      case None                             => timestamp
    }
  }

  val linkColor = expiring flatMap {
    case true => accents.accentColor.map(_.getColor())
    case false => Signal const getColor(R.color.accent_red);
  }

  val linkCallback = new Runnable() {
    def run() = for (z <- zms.head; m <- message.head) {
      if (m.state == Message.Status.FAILED || m.state == Message.Status.FAILED_READ) {
        z.messages.retryMessageSending(m.convId, m.id)
      }
    }
  }

  def onLikeClicked() = messageAndLikes.head.map { likesController.onLikeButtonClicked ! _ }

  private def statusString(timestamp: String, m: MessageData, isGroup: Boolean) =
    m.state match {
      case Status.PENDING              => getString(R.string.message_footer__status__sending)
      case Status.SENT                 => getString(R.string.message_footer__status__sent, timestamp)
      case Status.DELIVERED if isGroup => getString(R.string.message_footer__status__sent, timestamp)
      case Status.DELIVERED            => getString(R.string.message_footer__status__delivered, timestamp)
      case Status.DELETED              => getString(R.string.message_footer__status__deleted, timestamp)
      case Status.FAILED |
           Status.FAILED_READ          => getString(R.string.message_footer__status__failed)
      case _                           => timestamp
    }

  private def ephemeralTimeoutString(timestamp: String, remaining: FiniteDuration) = {

    def unitString(resId: Int, quantity: Long) =
      getQuantityString(resId, quantity.toInt, quantity.toString)

    lazy val years    = unitString(R.plurals.unit_years, remaining.toDays / 365)
    lazy val weeks    = unitString(R.plurals.unit_weeks,  (remaining.toDays % 365) / 7)
    lazy val days     = unitString(R.plurals.unit_days,  remaining.toDays % 7)
    lazy val hours    = f"${remaining.toHours % 24}:${remaining.toMinutes % 60}%02d"
    lazy val minutes  = unitString(R.plurals.unit_minutes, remaining.toMinutes % 60)
    lazy val seconds  = unitString(R.plurals.unit_seconds, remaining.toSeconds % 60)

    lazy val weeksNotZero    = Option((remaining.toDays % 365) / 7).filter(_ > 0).map(unitString(R.plurals.unit_weeks,  _))
    lazy val daysNotZero     = Option(remaining.toDays % 7).filter(_ > 0).map(unitString(R.plurals.unit_days,  _))

    val remainingTimeStamp =
      if (remaining > 365.days)      weeksNotZero.fold(getString(R.string.ephemeral_message_footer_single_unit, years))(getString(R.string.ephemeral_message_footer_multiple_units, years, _))
      else if (remaining > 7.days)   daysNotZero.fold(getString(R.string.ephemeral_message_footer_single_unit, weeks))(getString(R.string.ephemeral_message_footer_multiple_units, weeks, _))
      else if (remaining > 1.day)    getString(R.string.ephemeral_message_footer_multiple_units, days, hours)
      else if (remaining > 1.hour)   getString(R.string.ephemeral_message_footer_single_unit, hours)
      else if (remaining > 1.minute) getString(R.string.ephemeral_message_footer_single_unit, minutes)
      else                           getString(R.string.ephemeral_message_footer_single_unit, seconds)

    s"$timestamp \u30FB $remainingTimeStamp"
  }
}
