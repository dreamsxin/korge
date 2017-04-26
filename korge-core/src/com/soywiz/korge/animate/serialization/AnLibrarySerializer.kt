package com.soywiz.korge.animate.serialization

import com.soywiz.korge.animate.*
import com.soywiz.korge.view.ColorTransform
import com.soywiz.korim.bitmap.Bitmap
import com.soywiz.korim.format.ImageEncodingProps
import com.soywiz.korim.format.PNG
import com.soywiz.korim.format.writeBitmap
import com.soywiz.korio.serialization.json.Json
import com.soywiz.korio.stream.*
import com.soywiz.korio.util.clamp
import com.soywiz.korio.util.insert
import com.soywiz.korio.vfs.VfsFile
import com.soywiz.korma.Matrix2d
import com.soywiz.korma.geom.IRectangleInt
import com.soywiz.korma.geom.Rectangle

suspend fun AnLibrary.writeTo(file: VfsFile, config: AnLibrarySerializer.Config = AnLibrarySerializer.Config()) {
	//println("writeTo")
	val format = PNG()
	val props = ImageEncodingProps(config.compression)
	file.write(AnLibrarySerializer.gen(this, config = config, externalWriters = AnLibrarySerializer.ExternalWriters(
		writeAtlas = { index, atlas ->
			//showImageAndWait(atlas)
			file.withExtension("ani.$index.png").writeBitmap(atlas, format, props)
		},
		writeSound = { index, soundData ->
			file.withExtension("ani.$index.mp3").write(soundData)
		}
	)))
}

object AnLibrarySerializer {
	class ExternalWriters(
		val writeAtlas: suspend (index: Int, bitmap: Bitmap) -> Unit,
		val writeSound: suspend (index: Int, soundData: ByteArray) -> Unit
	)

	class Config(
		val compression: Double = 1.0,
		val keepPaths: Boolean = false,
		val mipmaps: Boolean = true
	)

	suspend fun gen(library: AnLibrary, config: Config = Config(), externalWriters: ExternalWriters): ByteArray = MemorySyncStreamToByteArray { write(this, library, config, externalWriters) }
	suspend fun write(s: SyncStream, library: AnLibrary, config: Config = Config(), externalWriters: ExternalWriters) = s.writeLibrary(library, config, externalWriters)

	private fun SyncStream.writeRect(r: Rectangle) {
		writeS_VL((r.x * 20).toInt())
		writeS_VL((r.y * 20).toInt())
		writeS_VL((r.width * 20).toInt())
		writeS_VL((r.height * 20).toInt())
	}

	private fun SyncStream.writeIRect(r: IRectangleInt) {
		writeS_VL(r.x)
		writeS_VL(r.y)
		writeS_VL(r.width)
		writeS_VL(r.height)
	}

	suspend private fun SyncStream.writeLibrary(lib: AnLibrary, config: Config, externalWriters: ExternalWriters) {
		writeStringz(AnLibraryFile.MAGIC, 8)
		writeU_VL(AnLibraryFile.VERSION)
		writeU_VL(lib.msPerFrame)
		writeU_VL(0
			.insert(config.mipmaps, 0)
		)
		// Allocate Strings
		val strings = OptimizedStringAllocator()
		for (symbol in lib.symbolsById) {
			strings.add(symbol.name)
			when (symbol) {
				is AnSymbolMovieClip -> {
					for (ss in symbol.states) {
						strings.add(ss.key)
						//strings.add(ss.value.state.name)
						strings.add(ss.value.subTimeline.nextState)
						for (timeline in ss.value.subTimeline.timelines) {
							for (entry in timeline.entries) {
								strings.add(entry.second.name)
							}
						}
					}
				}
				is AnTextFieldSymbol -> {
					strings.add(symbol.initialHtml)
				}
			}
		}
		strings.finalize()

		// String pool
		writeU_VL(strings.strings.size)
		for (str in strings.strings.drop(1)) writeStringVL(str!!)

		// Atlases
		val atlasBitmaps = listOf(
			lib.symbolsById.filterIsInstance<AnSymbolShape>().map { it.textureWithBitmap?.bitmapSlice?.bmp },
			lib.symbolsById.filterIsInstance<AnSymbolMorphShape>().flatMap { it.texturesWithBitmap.entries.map { it.second.bitmapSlice.bmp } }
		).flatMap { it }.filterNotNull().distinct()

		val atlasBitmapsToId = atlasBitmaps.withIndex().map { it.value to it.index }.toMap()

		writeU_VL(atlasBitmaps.size)
		for ((atlas, index) in atlasBitmapsToId) {
			externalWriters.writeAtlas(index, atlas)
		}

		val soundsToId = lib.symbolsById.filterIsInstance<AnSymbolSound>().withIndex().map { it.value to it.index }.toMap()

		writeU_VL(soundsToId.size)
		for ((sound, index) in soundsToId) {
			externalWriters.writeSound(index, sound.dataBytes ?: byteArrayOf())
		}

		// Symbols
		var morphShapeCount = 0
		var shapeCount = 0
		var movieClipCount = 0
		var totalFrameCount = 0
		var totalTimelines = 0

		writeU_VL(lib.symbolsById.size)
		for (symbol in lib.symbolsById) {
			writeU_VL(symbol.id)
			writeU_VL(strings[symbol.name])
			when (symbol) {
				is AnSymbolEmpty -> {
					writeU_VL(AnLibraryFile.SYMBOL_TYPE_EMPTY)
				}
				is AnSymbolSound -> {
					writeU_VL(AnLibraryFile.SYMBOL_TYPE_SOUND)
					writeU_VL(soundsToId[symbol]!!)
				}
				is AnTextFieldSymbol -> {
					writeU_VL(AnLibraryFile.SYMBOL_TYPE_TEXT)
					writeU_VL(strings[symbol.initialHtml])
					writeRect(symbol.bounds)
				}
				is AnSymbolShape -> {
					shapeCount++
					writeU_VL(AnLibraryFile.SYMBOL_TYPE_SHAPE)
					writeF32_le(symbol.textureWithBitmap!!.scale.toFloat())
					writeU_VL(atlasBitmapsToId[symbol.textureWithBitmap!!.bitmapSlice.bmp]!!)
					writeIRect(symbol.textureWithBitmap!!.bitmapSlice.bounds)
					writeRect(symbol.bounds)
					val path = symbol.path
					if (config.keepPaths && path != null) {
						writeU_VL(1)
						writeU_VL(path.commands.size)
						for (cmd in path.commands) write8(cmd)
						writeU_VL(path.data.size)
						for (v in path.data) writeF32_le(v.toFloat())
					} else {
						writeU_VL(0)
					}
				}
				is AnSymbolMorphShape -> {
					morphShapeCount++
					writeU_VL(AnLibraryFile.SYMBOL_TYPE_MORPH_SHAPE)
					val entries = symbol.texturesWithBitmap.entries
					writeU_VL(entries.size)
					for ((ratio1000, textureWithBitmap) in entries) {
						writeU_VL(ratio1000)
						writeF32_le(textureWithBitmap.scale.toFloat())
						writeU_VL(atlasBitmapsToId[textureWithBitmap.bitmapSlice.bmp]!!)
						writeRect(textureWithBitmap.bounds)
						writeIRect(textureWithBitmap.bitmapSlice.bounds)
					}
				}
				is AnSymbolBitmap -> {
					writeU_VL(AnLibraryFile.SYMBOL_TYPE_BITMAP)
				}
				is AnSymbolMovieClip -> {
					movieClipCount++
					// val totalDepths: Int, val totalFrames: Int, val totalUids: Int, val totalTime: Int
					writeU_VL(AnLibraryFile.SYMBOL_TYPE_MOVIE_CLIP)

					val hasNinePatchRect = (symbol.ninePatch != null)

					write8(0
						.insert(hasNinePatchRect, 0)
					)

					val limits = symbol.limits
					writeU_VL(limits.totalDepths)
					writeU_VL(limits.totalFrames)
					writeU_VL(limits.totalTime)

					// uids
					writeU_VL(limits.totalUids)
					for (uidInfo in symbol.uidInfo) {
						writeU_VL(uidInfo.characterId)
						writeStringVL(if (uidInfo.extraProps.isNotEmpty()) Json.encode(uidInfo.extraProps) else "")
					}

					val symbolStates = symbol.states.map { it.value.subTimeline }.toList().distinct()
					val symbolStateToIndex = symbolStates.withIndex().map { it.value to it.index }.toMap()

					if (hasNinePatchRect) {
						writeRect(symbol.ninePatch!!)
					}

					// states
					writeU_VL(symbolStates.size)
					for (ss in symbolStates) {
						//writeU_VL(strings[ss.name])
						writeU_VL(ss.totalTime)
						write8(0
							.insert(ss.nextStatePlay, 0)
						)
						writeU_VL(strings[ss.nextState])

						writeU_VL(ss.actions.size)
						for ((time, actions) in ss.actions.entries) {
							writeU_VL(time) // @TODO: Use time deltas and/or frame indices
							writeU_VL(actions.actions.size)
							for (action in actions.actions) {
								when (action) {
									is AnPlaySoundAction -> {
										write8(0)
										writeU_VL(action.soundId)
									}
								}
							}
						}

						for (timeline in ss.timelines) {
							totalTimelines++
							val frames = timeline.entries
							var lastUid = -1
							var lastName: String? = null
							var lastColorTransform: ColorTransform = ColorTransform()
							var lastMatrix: Matrix2d = Matrix2d()
							var lastClipDepth = -1
							var lastRatio = 0.0
							writeU_VL(frames.size)
							for ((frameTime, frame) in frames) {
								totalFrameCount++
								writeU_VL(frameTime) // @TODO: Use time deltas and/or frame indices

								val ct = frame.colorTransform
								val m = frame.transform
								val hasUid = frame.uid != lastUid
								val hasName = frame.name != lastName
								val hasColorTransform = ct != lastColorTransform
								val hasAlpha = (
									(ct.mR == lastColorTransform.mR) &&
										(ct.mG == lastColorTransform.mG) &&
										(ct.mB == lastColorTransform.mB) &&
										(ct.mA != lastColorTransform.mA) &&
										(ct.aR == lastColorTransform.aR) &&
										(ct.aG == lastColorTransform.aG) &&
										(ct.aB == lastColorTransform.aB) &&
										(ct.aA == lastColorTransform.aA)
									)


								val hasClipDepth = frame.clipDepth != lastClipDepth
								val hasRatio = frame.ratio != lastRatio

								val hasMatrix = m != lastMatrix

								write8(0
									.insert(hasUid, 0)
									.insert(hasName, 1)
									.insert(hasColorTransform, 2)
									.insert(hasMatrix, 3)
									.insert(hasClipDepth, 4)
									.insert(hasRatio, 5)
									.insert(hasAlpha, 6)
								)
								if (hasUid) writeU_VL(frame.uid)
								if (hasClipDepth) write16_le(frame.clipDepth)
								if (hasName) writeU_VL(strings[frame.name])

								if (hasAlpha) {
									write8((ct.mA * 255.0).toInt().clamp(0x00, 0xFF))
								} else if (hasColorTransform) {
									val hasMR = ct.mR != lastColorTransform.mR
									val hasMG = ct.mG != lastColorTransform.mG
									val hasMB = ct.mB != lastColorTransform.mB
									val hasMA = ct.mA != lastColorTransform.mA

									val hasAR = ct.aR != lastColorTransform.aR
									val hasAG = ct.aG != lastColorTransform.aG
									val hasAB = ct.aB != lastColorTransform.aB
									val hasAA = ct.aA != lastColorTransform.aA

									write8(0
										.insert(hasMR, 0)
										.insert(hasMG, 1)
										.insert(hasMB, 2)
										.insert(hasMA, 3)
										.insert(hasAR, 4)
										.insert(hasAG, 5)
										.insert(hasAB, 6)
										.insert(hasAA, 7)
									)

									if (hasMR) write8((ct.mR.clamp(0.0, 1.0) * 255.0).toInt())
									if (hasMG) write8((ct.mG.clamp(0.0, 1.0) * 255.0).toInt())
									if (hasMB) write8((ct.mB.clamp(0.0, 1.0) * 255.0).toInt())
									if (hasMA) write8((ct.mA.clamp(0.0, 1.0) * 255.0).toInt())
									if (hasAR) write8(ct.aR.clamp(-255, +255) / 2)
									if (hasAG) write8(ct.aG.clamp(-255, +255) / 2)
									if (hasAB) write8(ct.aB.clamp(-255, +255) / 2)
									if (hasAA) write8(ct.aA.clamp(-255, +255) / 2)
								}
								if (hasMatrix) {
									val hasMatrixA = m.a != lastMatrix.a
									val hasMatrixB = m.b != lastMatrix.b
									val hasMatrixC = m.c != lastMatrix.c
									val hasMatrixD = m.d != lastMatrix.d
									val hasMatrixTX = m.tx != lastMatrix.tx
									val hasMatrixTY = m.ty != lastMatrix.ty

									write8(0
										.insert(hasMatrixA, 0)
										.insert(hasMatrixB, 1)
										.insert(hasMatrixC, 2)
										.insert(hasMatrixD, 3)
										.insert(hasMatrixTX, 4)
										.insert(hasMatrixTY, 5)
									)

									if (hasMatrixA) writeS_VL((m.a * 16384).toInt())
									if (hasMatrixB) writeS_VL((m.b * 16384).toInt())
									if (hasMatrixC) writeS_VL((m.c * 16384).toInt())
									if (hasMatrixD) writeS_VL((m.d * 16384).toInt())
									if (hasMatrixTX) writeS_VL((m.tx * 20).toInt())
									if (hasMatrixTY) writeS_VL((m.ty * 20).toInt())
								}
								if (hasRatio) write8((frame.ratio * 255).toInt().clamp(0, 255))

								lastUid = frame.uid
								lastName = frame.name
								lastColorTransform = frame.colorTransform
								lastMatrix = m
								lastClipDepth = frame.clipDepth
								lastRatio = frame.ratio
							}
						}
					}

					// namedStates
					writeU_VL(symbol.states.size)
					for ((name, ssi) in symbol.states) {
						val stateIndex = symbolStateToIndex[ssi.subTimeline] ?: 0
						writeU_VL(strings[name])
						writeU_VL(ssi.startTime)
						writeU_VL(stateIndex)
					}
				}
			}
		}

		if (true) {
			//println("totalTimelines: $totalTimelines")
			//println("totalFrameCount: $totalFrameCount")
			//println("shapeCount: $shapeCount")
			//println("morphShapeCount: $morphShapeCount")
			//println("movieClipCount: $movieClipCount")
		}

		// End of symbols
	}
}
