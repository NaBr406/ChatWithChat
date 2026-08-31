package cn.nabr.chatwithchat.data.network

import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * A relay whose web console shares the API host answers an unrouted path with its SPA shell rather
 * than a 404: HTTP 200, `text/html`, and asset lines long enough to blow the SSE line cap. Checking
 * the content type up front names the misconfigured URL instead of failing half a megabyte later
 * with a sample of someone's inlined favicon.
 */
internal fun HttpResponse.htmlInsteadOfEventStreamErrorOrNull(): String? =
    htmlResponseErrorOrNull(contentType())

/**
 * Only `text/html` is refused. Providers that do stream correctly but mislabel the body
 * `application/json`, or send no content type at all, keep parsing, so the guard cannot break a
 * setup that already works.
 */
internal fun htmlResponseErrorOrNull(contentType: ContentType?): String? {
    if (contentType == null || !contentType.match(ContentType.Text.Html)) return null
    return "接口返回了网页而不是数据（Content-Type: $contentType），请检查平台的 API URL 是否指向 API 根地址。"
}
