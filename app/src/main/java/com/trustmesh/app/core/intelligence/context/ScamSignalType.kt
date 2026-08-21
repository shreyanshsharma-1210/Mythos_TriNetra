package com.trustmesh.app.core.intelligence.context

enum class ScamSignalCategory {
    IDENTITY,
    FINANCIAL,
    AUTHENTICATION,
    TELECOM,
    UTILITY,
    DELIVERY,
    GOVERNMENT,
    REMOTE_ACCESS,
    URGENCY,
    SOCIAL_ENGINEERING,
    LINK
}

enum class ScamSignalType(val category: ScamSignalCategory) {
    // IDENTITY
    UNKNOWN_CALLER(ScamSignalCategory.IDENTITY),
    UNRESOLVED_CALLER(ScamSignalCategory.IDENTITY),
    KNOWN_CONTACT(ScamSignalCategory.IDENTITY),
    TRUSTED_CALLER(ScamSignalCategory.IDENTITY),

    // FINANCIAL
    BANKING(ScamSignalCategory.FINANCIAL),
    UPI(ScamSignalCategory.FINANCIAL),
    PAYMENT(ScamSignalCategory.FINANCIAL),
    TRANSACTION(ScamSignalCategory.FINANCIAL),
    DEBIT(ScamSignalCategory.FINANCIAL),
    CREDIT(ScamSignalCategory.FINANCIAL),
    REFUND(ScamSignalCategory.FINANCIAL),
    REWARD(ScamSignalCategory.FINANCIAL),
    CASHBACK(ScamSignalCategory.FINANCIAL),
    ACCOUNT_SUSPENSION(ScamSignalCategory.FINANCIAL),

    // AUTHENTICATION
    OTP(ScamSignalCategory.AUTHENTICATION),
    VERIFICATION_CODE(ScamSignalCategory.AUTHENTICATION),
    LOGIN_CODE(ScamSignalCategory.AUTHENTICATION),
    SECURITY_CODE(ScamSignalCategory.AUTHENTICATION),
    PASSWORD_RESET(ScamSignalCategory.AUTHENTICATION),
    ACCOUNT_VERIFICATION(ScamSignalCategory.AUTHENTICATION),

    // TELECOM
    KYC_EXPIRY(ScamSignalCategory.TELECOM),
    SIM_BLOCK(ScamSignalCategory.TELECOM),
    MOBILE_DISCONNECTION(ScamSignalCategory.TELECOM),
    NUMBER_SUSPENSION(ScamSignalCategory.TELECOM),
    TRAI_IMPERSONATION(ScamSignalCategory.TELECOM),
    DOT_IMPERSONATION(ScamSignalCategory.TELECOM),

    // UTILITY
    ELECTRICITY_BILL(ScamSignalCategory.UTILITY),
    GAS_CONNECTION(ScamSignalCategory.UTILITY),
    WATER_BILL(ScamSignalCategory.UTILITY),
    BILL_DISCONNECTION(ScamSignalCategory.UTILITY),

    // DELIVERY
    PARCEL(ScamSignalCategory.DELIVERY),
    COURIER(ScamSignalCategory.DELIVERY),
    DELIVERY_FAILURE(ScamSignalCategory.DELIVERY),
    CUSTOMS(ScamSignalCategory.DELIVERY),
    PACKAGE_HOLD(ScamSignalCategory.DELIVERY),

    // GOVERNMENT
    GOVERNMENT_IMPERSONATION(ScamSignalCategory.GOVERNMENT),
    POLICE_IMPERSONATION(ScamSignalCategory.GOVERNMENT),
    TRAFFIC_CHALLAN(ScamSignalCategory.GOVERNMENT),
    ECHALLAN(ScamSignalCategory.GOVERNMENT),
    COURT_NOTICE(ScamSignalCategory.GOVERNMENT),
    TAX_NOTICE(ScamSignalCategory.GOVERNMENT),

    // REMOTE ACCESS
    APK_INSTALL_REQUEST(ScamSignalCategory.REMOTE_ACCESS),
    UNKNOWN_APP_INSTALL(ScamSignalCategory.REMOTE_ACCESS),
    REMOTE_ACCESS_APP(ScamSignalCategory.REMOTE_ACCESS),
    SCREEN_SHARING_REQUEST(ScamSignalCategory.REMOTE_ACCESS),

    // URGENCY
    IMMEDIATE_ACTION(ScamSignalCategory.URGENCY),
    DEADLINE(ScamSignalCategory.URGENCY),
    DISCONNECTION_THREAT(ScamSignalCategory.URGENCY),
    ACCOUNT_BLOCK_THREAT(ScamSignalCategory.URGENCY),
    LEGAL_THREAT(ScamSignalCategory.URGENCY),

    // SOCIAL ENGINEERING
    CALL_THIS_NUMBER(ScamSignalCategory.SOCIAL_ENGINEERING),
    CONTACT_AGENT(ScamSignalCategory.SOCIAL_ENGINEERING),
    SHARE_DETAILS(ScamSignalCategory.SOCIAL_ENGINEERING),
    SHARE_OTP(ScamSignalCategory.SOCIAL_ENGINEERING),
    SHARE_PIN(ScamSignalCategory.SOCIAL_ENGINEERING),
    SHARE_PASSWORD(ScamSignalCategory.SOCIAL_ENGINEERING),
    SEND_MONEY(ScamSignalCategory.SOCIAL_ENGINEERING),
    UPI_REQUEST(ScamSignalCategory.SOCIAL_ENGINEERING),
    REMOTE_SUPPORT(ScamSignalCategory.SOCIAL_ENGINEERING),

    // LINK
    SUSPICIOUS_URL(ScamSignalCategory.LINK),
    SHORTENED_URL(ScamSignalCategory.LINK),
    IP_ADDRESS_URL(ScamSignalCategory.LINK),
    NON_EXPECTED_DOMAIN(ScamSignalCategory.LINK),
    URL_WITH_URGENCY(ScamSignalCategory.LINK)
}

enum class Confidence {
    LOW, MEDIUM, HIGH
}

data class RiskSignal(
    val type: ScamSignalType,
    val confidence: Confidence,
    val weight: Int,
    val source: String,
    val explanation: String
)
