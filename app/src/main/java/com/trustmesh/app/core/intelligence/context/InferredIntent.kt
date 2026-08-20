package com.trustmesh.app.core.intelligence.context

enum class InferredIntent {
    UNKNOWN,
    POSSIBLE_SOCIAL_ENGINEERING,
    POSSIBLE_FINANCIAL_FRAUD,
    POSSIBLE_OTP_THEFT,
    POSSIBLE_ACCOUNT_TAKEOVER
}
