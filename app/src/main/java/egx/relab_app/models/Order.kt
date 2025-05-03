package egx.relab_app.models

data class Order(
    val id: Int? = null,
    val orderNumber: String,
    val customer: String,
    val contactInfo: String,
    val extraInfo: String,
    val telegram: String,
    val deviceName: String,
    val deviceType: String,
    val manufacturer: String,
    val model: String,
    val kit: String,
    val photoUrl: String?,
    val description: String,
    val date: String,
    val orderType: String,
    val status: String
)
