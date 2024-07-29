package ee.carlrobert.codegpt.events

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class CodeGPTEvent @JsonCreator constructor(
    @JsonProperty("event") val event: Event
)

data class Event @JsonCreator constructor(
    @JsonProperty("type") val type: String,
    @JsonProperty("details") val details: Details
)

data class Details @JsonCreator constructor(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("displayUrl") val displayUrl: String
)
