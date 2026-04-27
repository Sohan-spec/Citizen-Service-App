package com.sohanreddy.caci

import android.animation.TypeEvaluator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker

object MarkerAnimation {

    fun animateMarkerTo(marker: Marker, finalPosition: LatLng) {
        val startPosition = marker.position
        val animator = ValueAnimator.ofObject(LatLngEvaluator(), startPosition, finalPosition)
        animator.duration = 2500L
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { valueAnimator ->
            val animatedPosition = valueAnimator.animatedValue as LatLng
            marker.position = animatedPosition
        }
        animator.start()
    }

    private class LatLngEvaluator : TypeEvaluator<LatLng> {
        override fun evaluate(fraction: Float, startValue: LatLng, endValue: LatLng): LatLng {
            val lat = (endValue.latitude - startValue.latitude) * fraction + startValue.latitude
            val lng = (endValue.longitude - startValue.longitude) * fraction + startValue.longitude
            return LatLng(lat, lng)
        }
    }
}
