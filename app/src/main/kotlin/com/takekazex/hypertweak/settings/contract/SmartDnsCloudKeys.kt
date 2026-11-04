package com.takekazex.hypertweak.settings.contract

/** MIUI cloud keys and the resolver properties they publish. */
object SmartDnsCloudKeys {
    const val MODE_DEFAULT = 0
    const val MODE_ON = 1
    const val MODE_OFF = 2

    const val SETTING_SMARTDNS_V1 = "cloud_smartdns_enabled"
    const val SETTING_SMARTDNS_V2 = "cloud_smartdns_enabled_v2"
    const val SETTING_HAPPY_EYEBALLS = "cloud_dns_happy_eyeballs_priority_enabled"

    const val PROPERTY_SMARTDNS_ENABLE = "persist.device_config.netd_native.smartdns_enable"
    const val PROPERTY_SMARTDNS_IPV4_FIRST = "persist.device_config.netd_native.smartdns_ipv4_first"
    const val PROPERTY_HAPPY_EYEBALLS = "persist.device_config.netd_native.happy_eyeballs_enable"
}
