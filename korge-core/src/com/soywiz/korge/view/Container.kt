package com.soywiz.korge.view

import com.soywiz.korge.render.RenderContext
import com.soywiz.korma.geom.BoundsBuilder
import com.soywiz.korma.geom.Rectangle

open class Container(views: Views) : View(views) {
	val children = arrayListOf<View>()

	fun removeChildren() {
		for (child in children) {
			child.parent = null
			child.index = -1
		}
		children.clear()
	}

	fun addChild(view: View) = this.plusAssign(view)

	override fun invalidate() {
		validGlobal = false
		for (child in children) {
			if (!child.validGlobal) continue
			child.validGlobal = false
			child.invalidate()
		}
	}

	operator fun plusAssign(view: View) {
		view.removeFromParent()
		view.index = children.size
		children += view
		view.parent = this
	}

	override fun render(ctx: RenderContext) {
		for (child in children) child.render(ctx)
	}

	override fun hitTest(x: Double, y: Double): View? {
		for (child in children.reversed()) return child.hitTest(x, y) ?: continue
		return null
	}

	private val bb = BoundsBuilder()
	private val tempRect = Rectangle()
	override fun getLocalBounds(out: Rectangle) {
		bb.reset()
		for (child in children) {
			child.getBounds(this, tempRect)
			bb.add(tempRect)
		}
		bb.getBounds(out)
	}

	override fun updateInternal(dtMs: Int) {
		super.updateInternal(dtMs)
		for (child in children) child.update(dtMs)
	}
}
