// ! Bu araç @programmer tarafından.

package com.programmer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizillaResponse(
    @JsonProperty("result") val result: List<DizillaSeries>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizillaSeries(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("image") val image: String? = null
)
