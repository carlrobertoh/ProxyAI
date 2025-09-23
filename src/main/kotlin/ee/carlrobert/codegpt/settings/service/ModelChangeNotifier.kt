package ee.carlrobert.codegpt.settings.service

import com.intellij.util.messages.Topic

interface ModelChangeNotifier {

    fun chatModelChanged(newModel: String, serviceType: ServiceType)
    fun codeModelChanged(newModel: String, serviceType: ServiceType)
    fun autoApplyModelChanged(newModel: String, serviceType: ServiceType)
    fun commitMessageModelChanged(newModel: String, serviceType: ServiceType)
    fun inlineEditModelChanged(newModel: String, serviceType: ServiceType)
    fun nextEditModelChanged(newModel: String, serviceType: ServiceType)
    fun nameLookupModelChanged(newModel: String, serviceType: ServiceType)
    fun modelChanged(featureType: FeatureType, newModel: String, serviceType: ServiceType)

    companion object {
        @JvmField
        val MODEL_CHANGE_TOPIC: Topic<ModelChangeNotifier> =
            Topic.create("modelChange", ModelChangeNotifier::class.java)

        @JvmStatic
        fun getTopic(): Topic<ModelChangeNotifier> = MODEL_CHANGE_TOPIC
    }
}

abstract class ModelChangeNotifierAdapter : ModelChangeNotifier {

    override fun chatModelChanged(newModel: String, serviceType: ServiceType) {}
    override fun codeModelChanged(newModel: String, serviceType: ServiceType) {}
    override fun autoApplyModelChanged(newModel: String, serviceType: ServiceType) {}
    override fun commitMessageModelChanged(newModel: String, serviceType: ServiceType) {}
    override fun inlineEditModelChanged(newModel: String, serviceType: ServiceType) {}
    override fun nextEditModelChanged(newModel: String, serviceType: ServiceType) {}
    override fun nameLookupModelChanged(newModel: String, serviceType: ServiceType) {}
    override fun modelChanged(
        featureType: FeatureType,
        newModel: String,
        serviceType: ServiceType
    ) {
    }
}