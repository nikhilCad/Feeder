package com.nononsenseapps.feeder.ui.text

import android.graphics.Point
import android.text.Spanned
import org.ccil.cowan.tagsoup.HTMLSchema
import org.ccil.cowan.tagsoup.Parser
import org.kodein.di.DI
import java.io.Reader
import java.net.URL

val schema: HTMLSchema by lazy { HTMLSchema() }

fun toSpannedWithImages(
    di: DI,
    source: Reader,
    siteUrl: URL,
    maxSize: Point,
    spannableStringBuilder: SensibleSpannableStringBuilder = SensibleSpannableStringBuilder(),
    urlClickListener: UrlClickListener?
): Spanned {
    val parser = Parser()
    try {
        parser.setProperty(Parser.schemaProperty, schema)
    } catch (e: org.xml.sax.SAXNotRecognizedException) {
        // Should not happen.
        throw RuntimeException(e)
    } catch (e: org.xml.sax.SAXNotSupportedException) {
        throw RuntimeException(e)
    }

    val converter = CoilConverter(di, source, siteUrl, parser, maxSize, spannableStringBuilder, urlClickListener = urlClickListener)
    return converter.convert()
}

/**
 * Returns displayable styled text from the provided HTML string.
 * Any &lt;img&gt; tags in the HTML will use the specified ImageGetter
 * to request a representation of the image (use null if you don't
 * want this) and the specified TagHandler to handle unknown tags
 * (specify null if you don't want this).
 *
 *
 *
 * This uses TagSoup to handle real HTML, including all of the brokenness
 * found in the wild.
 */
fun toSpannedWithNoImages(
    di: DI,
    source: Reader,
    siteUrl: URL,
    maxSize: Point,
    spannableStringBuilder: SensibleSpannableStringBuilder = SensibleSpannableStringBuilder(),
    urlClickListener: UrlClickListener?
): Spanned {
    val parser = Parser()
    try {
        parser.setProperty(Parser.schemaProperty, schema)
    } catch (e: org.xml.sax.SAXNotRecognizedException) {
        // Should not happen.
        throw RuntimeException(e)
    } catch (e: org.xml.sax.SAXNotSupportedException) {
        throw RuntimeException(e)
    }

    val converter = HtmlToSpannedConverter(source, siteUrl, parser, di, maxSize, spannableStringBuilder, urlClickListener = urlClickListener)
    return converter.convert()
}
