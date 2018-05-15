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
package com.waz.zclient.calling

import java.util.concurrent.TimeUnit

import android.content.Context
import android.os.Bundle
import android.support.annotation.Nullable
import android.support.v4.app.Fragment
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view._
import android.widget.FrameLayout.LayoutParams
import android.widget.{FrameLayout, TextView}
import com.waz.ZLog.ImplicitTag.implicitLogTag
import com.waz.ZLog.verbose
import com.waz.utils.events.Subscription
import com.waz.utils.returning
import com.waz.zclient.R
import com.waz.zclient.calling.controllers.CallController
import com.waz.zclient.calling.views.{CallingHeader, CallingMiddleLayout, ControlsView}
import com.waz.zclient.utils.RichView

import scala.concurrent.duration.FiniteDuration

class ControlsFragment extends FadingControls {

  implicit def ctx: Context = getActivity

  private lazy val controller = inject[CallController]

  private lazy val degradedWarningTextView = returning(view[TextView](R.id.degraded_warning)) { vh =>
    controller.convDegraded.onUi { degraded => vh.foreach(_.setVisible(degraded))}
    controller.degradationWarningText.onUi { text => vh.foreach(_.setText(text))}
  }

  private lazy val degradedConfirmationTextView = returning(view[TextView](R.id.degraded_confirmation)) { vh =>
    controller.convDegraded.onUi { degraded => vh.foreach(_.setVisible(degraded))}
    controller.degradationConfirmationText.onUi { text => vh.foreach(_.setText(text))}
  }

  private lazy val callingHeader   = view[CallingHeader](R.id.calling_header)
  private lazy val callingMiddle   = view[CallingMiddleLayout](R.id.calling_middle)
  private lazy val callingControls = view[ControlsView](R.id.controls_grid)

  private lazy val messageView = returning(view[TextView](R.id.video_warning_message)) { vh =>
    val startedAsVideo = controller.isVideoCall.currentValue.getOrElse(false)

    controller.stateMessageText.onUi {
      case (Some(message)) if startedAsVideo =>
        vh.foreach { messageView =>
          messageView.setVisible(true)
          messageView.setText(message)
          verbose(s"messageView text: $message")
        }
      case _ =>
        verbose("messageView gone")
        vh.foreach(_.setVisible(false))
    }
  }

  override def onCreateView(inflater: LayoutInflater, viewGroup: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.fragment_calling_controls, viewGroup, false)

  override def onViewCreated(v: View, @Nullable savedInstanceState: Bundle): Unit = {
    super.onViewCreated(v, savedInstanceState)

    getActivity.getWindow.setBackgroundDrawableResource(R.color.calling_background)

    degradedWarningTextView
    degradedConfirmationTextView
    callingHeader
    callingMiddle
    callingControls
    messageView

    setFadingControls(
      callingHeader.get.nameView,
      callingHeader.get.subtitleView,
      callingHeader.get.bitRateModeView,
      callingMiddle.get,
      callingControls.get
    )

    controller.isCallActive.onUi {
      case false =>
        verbose("call no longer exists, finishing activity")
        getActivity.finish()
      case _ =>
    }

    controller.callConvId.onChanged.onUi(_ => restart())

    //ensure activity gets killed to allow content to change if the conv degrades (no need to kill activity on audio call)
    (for {
      degraded <- controller.convDegraded
      video    <- controller.isVideoCall
    } yield degraded && video).onChanged.filter(_ == true).onUi(_ => getActivity.finish())

    v.onClick(if (controller.showVideoView.currentValue.getOrElse(false)) toggleControlVisibility())

    callingHeader.foreach(_.closeButton.onClick {
      verbose("close click")
    })
  }

  private var subs = Set[Subscription]()


  override def onStart(): Unit = {
    super.onStart()

    callingControls.foreach(controls =>
      subs += controls.onButtonClick.onUi(_ => if (controller.showVideoView.currentValue.getOrElse(false)) extendControlsDisplay())
    )

    callingMiddle.foreach(vh => subs += vh.onShowAllClicked.onUi { _ =>
      getFragmentManager.beginTransaction
        .setCustomAnimations(
          R.anim.fragment_animation_second_page_slide_in_from_right,
          R.anim.fragment_animation_second_page_slide_out_to_left,
          R.anim.fragment_animation_second_page_slide_in_from_left,
          R.anim.fragment_animation_second_page_slide_out_to_right)
        .replace(R.id.controls_layout, CallParticipantsFragment(), CallParticipantsFragment.Tag)
        .addToBackStack(CallParticipantsFragment.Tag)
        .commit
    })
  }

  override def onResume() = {
    super.onResume()
    controller.callControlsVisible ! true
  }


  override def onPause() = {
    controller.callControlsVisible ! false
    super.onPause()
  }

  override def onStop(): Unit = {
    subs.foreach(_.destroy())
    subs = Set.empty
    super.onStop()
  }

  private def restart() = {
    verbose("restart")
    getActivity.finish()
    CallingActivity.start(ctx)
    getActivity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
  }

}

object ControlsFragment {
  val VideoViewTag = "VIDEO_VIEW_TAG"
  val TapDelay = FiniteDuration(3000, TimeUnit.MILLISECONDS)
  val Tag = classOf[ControlsFragment].getName

  def newInstance: Fragment = new ControlsFragment

  /**
    * Ensures there's only ever one video TextureView in a layout, and that it's always at the bottom. Both this layout
    * and the RoundedLayout for the small self-preview extend FrameLayout, so hopefully enforcing FrameLayout here
    * should break early if anything changes.
    */
  def addVideoViewToLayout(layout: FrameLayout, videoView: View) = {
    removeVideoViewFromParent(videoView) //in case the videoView belongs to another parent
    removeVideoViewFromLayoutByTag(layout) //in case the layout has another videoView

    videoView.setTag(ControlsFragment.VideoViewTag)
    layout.addView(videoView, 0, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
  }

  /**
    * Needed to remove a TextureView from its parent in case we try and set it as a child of a different layout
    * (the self-preview TextureView moves from fullscreen to the small layout when call is answered)
    */
  private def removeVideoViewFromParent(videoView: View): Unit =
    Option(videoView.getParent.asInstanceOf[ViewGroup]).foreach(_.removeView(videoView))

  private def removeVideoViewFromLayoutByTag(layout: ViewGroup): Unit =
    findVideoView(layout).foreach(layout.removeView)

  private def findVideoView(layout: ViewGroup) =
    for {
      v <- Option(layout.getChildAt(0))
      t <- Option(v.getTag) if t == ControlsFragment.VideoViewTag
    } yield v
}