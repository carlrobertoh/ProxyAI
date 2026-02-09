package ee.carlrobert.codegpt.settings.service.custom

data class CustomServiceHeaderRow(
    val key: String,
    val value: String,
)

data class CustomServiceBodyRow(
    val key: String,
    val type: CustomServiceBodyValueType,
    val value: String,
)
