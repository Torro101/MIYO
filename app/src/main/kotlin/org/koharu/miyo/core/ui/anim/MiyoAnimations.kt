package org.koharu.miyo.core.ui.anim

import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import org.koharu.miyo.R

/**
 * MIYO UI/UX Redesign - Refined RecyclerView item animator.
 *
 * Drop-in replacement for [DefaultItemAnimator] that adds subtle staggered
 * entrance animations for newly-added items (fade + slight slide-up).
 *
 * Use:
 * ```
 *   recyclerView.itemAnimator = MiyoItemAnimator()
 * ```
 *
 * Behaviour:
 *  - Add: fade-in + 24dp slide-up over 240ms.
 *  - Remove: fade-out + slide-down over 160ms.
 *  - Move/change: default [DefaultItemAnimator] behaviour.
 *  - Honors the system animator scale.
 *
 * This is OPT-IN: existing adapters continue to use the platform default
 * [DefaultItemAnimator] unless explicitly switched.
 */
class MiyoItemAnimator : DefaultItemAnimator() {

    override fun animateAdd(holder: RecyclerView.ViewHolder?): Boolean {
        if (holder == null) return false
        val view = holder.itemView
        view.alpha = 0f
        view.translationY = view.resources.getDimension(R.dimen.miyo_stagger_translation)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                view.context,
                android.R.interpolator.fast_out_slow_in,
            ))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dispatchAddFinished(holder)
                }
            })
            .start()
        dispatchAddStarting(holder)
        return false
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder?): Boolean {
        if (holder == null) return false
        val view = holder.itemView
        view.animate()
            .alpha(0f)
            .translationY(view.resources.getDimension(R.dimen.miyo_stagger_translation))
            .setDuration(160)
            .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                view.context,
                android.R.interpolator.fast_out_linear_in,
            ))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.alpha = 1f
                    view.translationY = 0f
                    dispatchRemoveFinished(holder)
                }
            })
            .start()
        dispatchRemoveStarting(holder)
        return false
    }

    override fun endAnimation(holder: RecyclerView.ViewHolder) {
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
        super.endAnimation(holder)
    }
}

/**
 * Apply a subtle pop-in animation to any view. Useful for badges,
 * chips, FABs, and small indicators that should appear with a tactile cue.
 */
fun View.popIn(duration: Long = 180L) {
    if (!isVisible) return
    animate().cancel()
    alpha = 0f
    scaleX = 0.7f
    scaleY = 0.7f
    animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(duration)
        .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
            context,
            android.R.interpolator.fast_out_slow_in,
        ))
        .start()
}

/**
 * Apply a subtle pop-out animation. The view's visibility is set to GONE
 * on completion so subsequent layouts reflow correctly.
 */
fun View.popOut(duration: Long = 120L, onEnd: () -> Unit = {}) {
    if (!isVisible) {
        onEnd()
        return
    }
    animate().cancel()
    animate()
        .alpha(0f)
        .scaleX(0.7f)
        .scaleY(0.7f)
        .setDuration(duration)
        .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
            context,
            android.R.interpolator.fast_out_linear_in,
        ))
        .setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                visibility = View.GONE
                onEnd()
            }
        })
        .start()
}

/**
 * Apply a subtle fade-through animation for tab content switches.
 * Use this on a container view when switching tabs to give a smooth
 * M3-style transition rather than an abrupt content swap.
 */
fun View.fadeThroughIn(duration: Long = 180L) {
    animate().cancel()
    alpha = 0f
    scaleX = 0.96f
    scaleY = 0.96f
    animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(duration)
        .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
            context,
            android.R.interpolator.fast_out_slow_in,
        ))
        .start()
}
