package net.stupidtech.sky.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Airline logos by IATA code, fetched at runtime from the Aviasales image CDN (pics.avs.io).
 * Each logo is drawn on a small white card so it stays readable on any map style.
 */
object Logos {
    private const val W = 240
    private const val H = 120
    private const val PAD = 12

    private val cards = ConcurrentHashMap<String, Bitmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun card(iata: String): Bitmap? = cards[iata]
    fun isKnown(iata: String) = cards.containsKey(iata) || iata in missing

    /** Returns true if a logo is now available for [iata]. */
    suspend fun load(iata: String): Boolean {
        if (cards.containsKey(iata)) return true
        if (iata in missing) return false
        val bytes = try {
            FlightData.fetchBytes("https://pics.avs.io/$W/$H/$iata.png")
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotFoundException) {
            missing += iata
            return false
        } catch (e: Exception) {
            return false // transient; try again later
        }
        val logo = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
            missing += iata
            return false
        }
        cards[iata] = toCard(logo)
        return true
    }

    private fun toCard(logo: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(W + PAD * 2, H + PAD * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = RectF(2f, 2f, out.width - 2f, out.height - 2f)
        c.drawRoundRect(rect, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawRoundRect(
            rect, 22f, 22f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.argb(90, 0, 0, 0)
            },
        )
        c.drawBitmap(logo, null, RectF(PAD.toFloat(), PAD.toFloat(), (PAD + W).toFloat(), (PAD + H).toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
