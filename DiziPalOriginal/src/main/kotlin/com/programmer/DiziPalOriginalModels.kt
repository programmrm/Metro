// ! Bu araç @programmer tarafından.

package com.programmer

import com.fasterxml.jackson.annotation.JsonProperty


data class DizipalSearchData(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("results") val results: List<DizipalSearchResult>?
)

data class DizipalSearchResult(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("rating") val rating: String?
)

// /ajax-player-config yanıtı: embed URL şifreli (enc) olarak geliyor
data class DizipalPlayerConfigResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("message") val message: String?,
    @JsonProperty("config")  val config: DizipalPlayerConfig?,
    @JsonProperty("enc")     val enc: DizipalPlayerEnc?
)

data class DizipalPlayerConfig(
    @JsonProperty("v") val v: String?,   // embed URL (şifresiz geldiyse dolu)
    @JsonProperty("t") val t: String?,   // "embed"
    @JsonProperty("p") val p: String?    // afiş
)

data class DizipalPlayerEnc(
    @JsonProperty("c")  val c: String?,   // AES-256-CBC şifreli metin
    @JsonProperty("iv") val iv: String?,
    @JsonProperty("k1") val k1: String?,  // anahtar parçası 1 (base64)
    @JsonProperty("k2") val k2: String?   // anahtar parçası 2 (base64)
)
