package com.trustmesh.app.core.intelligence.context

import com.trustmesh.app.core.events.SecurityEvent
import java.util.Locale

object SmsIntentClassifier {

    fun classify(text: String, title: String): List<RiskSignal> {
        val signals = mutableListOf<RiskSignal>()
        val normalized = normalizeText("$title $text")

        detectFinancial(normalized, signals)
        detectAuthentication(normalized, signals)
        detectTelecom(normalized, signals)
        detectUtility(normalized, signals)
        detectDelivery(normalized, signals)
        detectGovernment(normalized, signals)
        detectRemoteAccess(normalized, signals)
        detectUrgency(normalized, signals)
        detectSocialEngineering(normalized, signals)

        return signals
    }

    private fun normalizeText(input: String): String {
        return input.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun detectFinancial(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("bank") || text.contains("hdfc") || text.contains("sbi") || text.contains("icici")) {
            signals.add(RiskSignal(ScamSignalType.BANKING, Confidence.HIGH, 15, "SmsIntent", "Banking context detected"))
        }
        if (text.contains("upi") || text.contains("phonepe") || text.contains("gpay") || text.contains("paytm")) {
            signals.add(RiskSignal(ScamSignalType.UPI, Confidence.HIGH, 15, "SmsIntent", "UPI context detected"))
        }
        if (text.contains("payment") || text.contains("pay") || text.contains("paid")) {
            signals.add(RiskSignal(ScamSignalType.PAYMENT, Confidence.MEDIUM, 10, "SmsIntent", "Payment context detected"))
        }
        if (text.contains("transaction") || text.contains("txn")) {
            signals.add(RiskSignal(ScamSignalType.TRANSACTION, Confidence.MEDIUM, 10, "SmsIntent", "Transaction context detected"))
        }
        if (text.contains("debited") || text.contains("deducted") || text.contains("dr ")) {
            signals.add(RiskSignal(ScamSignalType.DEBIT, Confidence.HIGH, 15, "SmsIntent", "Account debit alert"))
        }
        if (text.contains("credited") || text.contains("cr ")) {
            signals.add(RiskSignal(ScamSignalType.CREDIT, Confidence.HIGH, 10, "SmsIntent", "Account credit alert"))
        }
        if (text.contains("refund")) {
            signals.add(RiskSignal(ScamSignalType.REFUND, Confidence.HIGH, 10, "SmsIntent", "Refund context detected"))
        }
        if (text.contains("reward") || text.contains("prize") || text.contains("lucky winner")) {
            signals.add(RiskSignal(ScamSignalType.REWARD, Confidence.HIGH, 15, "SmsIntent", "Reward or prize detected"))
        }
        if (text.contains("cashback")) {
            signals.add(RiskSignal(ScamSignalType.CASHBACK, Confidence.HIGH, 15, "SmsIntent", "Cashback detected"))
        }
        if (text.contains("account suspended") || text.contains("account block") || text.contains("account closed")) {
            signals.add(RiskSignal(ScamSignalType.ACCOUNT_SUSPENSION, Confidence.HIGH, 25, "SmsIntent", "Account suspension threat"))
        }
    }

    private fun detectAuthentication(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("otp") || text.contains("one time password")) {
            signals.add(RiskSignal(ScamSignalType.OTP, Confidence.HIGH, 8, "SmsIntent", "OTP detected"))
        }
        if (text.contains("verification code") || text.contains("security code") || text.contains("login code")) {
            signals.add(RiskSignal(ScamSignalType.VERIFICATION_CODE, Confidence.HIGH, 8, "SmsIntent", "Verification code detected"))
        }
        if (text.contains("password reset")) {
            signals.add(RiskSignal(ScamSignalType.PASSWORD_RESET, Confidence.HIGH, 15, "SmsIntent", "Password reset requested"))
        }
        if (text.contains("account verification") || text.contains("verify account")) {
            signals.add(RiskSignal(ScamSignalType.ACCOUNT_VERIFICATION, Confidence.HIGH, 10, "SmsIntent", "Account verification request"))
        }
        if (Regex("\\b\\d{4}\\b").containsMatchIn(text) || Regex("\\b\\d{6}\\b").containsMatchIn(text)) {
             // If we already have OTP, skip it, else add it
             if (signals.none { it.type == ScamSignalType.OTP || it.type == ScamSignalType.VERIFICATION_CODE }) {
                 signals.add(RiskSignal(ScamSignalType.VERIFICATION_CODE, Confidence.MEDIUM, 5, "SmsIntent", "Potential verification code (4-6 digits) detected"))
             }
        }
    }

    private fun detectTelecom(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("kyc exp") || text.contains("kyc updat") || text.contains("re kyc") || text.contains("verify kyc")) {
            signals.add(RiskSignal(ScamSignalType.KYC_EXPIRY, Confidence.HIGH, 15, "SmsIntent", "KYC update request"))
        }
        if (text.contains("sim block") || text.contains("sim will be block") || text.contains("sim verif")) {
            signals.add(RiskSignal(ScamSignalType.SIM_BLOCK, Confidence.HIGH, 20, "SmsIntent", "SIM block threat"))
        }
        if (text.contains("number disconnect") || text.contains("mobile disconnect") || text.contains("service suspend")) {
            signals.add(RiskSignal(ScamSignalType.MOBILE_DISCONNECTION, Confidence.HIGH, 20, "SmsIntent", "Mobile disconnection threat"))
        }
        if (text.contains("trai ")) {
            signals.add(RiskSignal(ScamSignalType.TRAI_IMPERSONATION, Confidence.HIGH, 15, "SmsIntent", "Mentions TRAI"))
        }
        if (text.contains("dept of telecom") || text.contains("dot ")) {
            signals.add(RiskSignal(ScamSignalType.DOT_IMPERSONATION, Confidence.MEDIUM, 10, "SmsIntent", "Mentions DoT"))
        }
    }

    private fun detectUtility(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("electricity") || text.contains("power bill") || text.contains("bijli")) {
            signals.add(RiskSignal(ScamSignalType.ELECTRICITY_BILL, Confidence.HIGH, 10, "SmsIntent", "Electricity bill context"))
        }
        if (text.contains("gas connect") || text.contains("png ")) {
            signals.add(RiskSignal(ScamSignalType.GAS_CONNECTION, Confidence.MEDIUM, 10, "SmsIntent", "Gas connection context"))
        }
        if (text.contains("water bill")) {
            signals.add(RiskSignal(ScamSignalType.WATER_BILL, Confidence.MEDIUM, 10, "SmsIntent", "Water bill context"))
        }
        if (text.contains("power cut") || text.contains("disconnect tonight") || text.contains("power disconnect")) {
            signals.add(RiskSignal(ScamSignalType.BILL_DISCONNECTION, Confidence.HIGH, 20, "SmsIntent", "Utility disconnection threat"))
        }
    }

    private fun detectDelivery(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("parcel") || text.contains("package")) {
            signals.add(RiskSignal(ScamSignalType.PARCEL, Confidence.HIGH, 10, "SmsIntent", "Parcel or package context"))
        }
        if (text.contains("courier") || text.contains("bluedart") || text.contains("fedex") || text.contains("delhivery")) {
            signals.add(RiskSignal(ScamSignalType.COURIER, Confidence.HIGH, 10, "SmsIntent", "Courier context"))
        }
        if (text.contains("delivery fail") || text.contains("address wrong") || text.contains("undelivered")) {
            signals.add(RiskSignal(ScamSignalType.DELIVERY_FAILURE, Confidence.HIGH, 15, "SmsIntent", "Delivery failure alert"))
        }
        if (text.contains("customs") || text.contains("custom fee")) {
            signals.add(RiskSignal(ScamSignalType.CUSTOMS, Confidence.HIGH, 20, "SmsIntent", "Customs clearance context"))
        }
        if (text.contains("package hold") || text.contains("parcel block")) {
            signals.add(RiskSignal(ScamSignalType.PACKAGE_HOLD, Confidence.HIGH, 15, "SmsIntent", "Package hold alert"))
        }
    }

    private fun detectGovernment(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("gov ") || text.contains("government") || text.contains("income tax")) {
            signals.add(RiskSignal(ScamSignalType.GOVERNMENT_IMPERSONATION, Confidence.MEDIUM, 10, "SmsIntent", "Government context"))
        }
        if (text.contains("police") || text.contains("cyber cell") || text.contains("cbi ")) {
            signals.add(RiskSignal(ScamSignalType.POLICE_IMPERSONATION, Confidence.HIGH, 15, "SmsIntent", "Police/Enforcement context"))
        }
        if (text.contains("traffic challan") || text.contains("fine unpaid")) {
            signals.add(RiskSignal(ScamSignalType.TRAFFIC_CHALLAN, Confidence.HIGH, 10, "SmsIntent", "Traffic challan context"))
        }
        if (text.contains("echallan") || text.contains("e challan")) {
            signals.add(RiskSignal(ScamSignalType.ECHALLAN, Confidence.HIGH, 10, "SmsIntent", "e-Challan context"))
        }
        if (text.contains("court notice") || text.contains("supreme court") || text.contains("high court")) {
            signals.add(RiskSignal(ScamSignalType.COURT_NOTICE, Confidence.HIGH, 15, "SmsIntent", "Court notice context"))
        }
        if (text.contains("tax notice") || text.contains("tds pend")) {
            signals.add(RiskSignal(ScamSignalType.TAX_NOTICE, Confidence.HIGH, 15, "SmsIntent", "Tax notice context"))
        }
    }

    private fun detectRemoteAccess(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("apk") || text.contains("install app") || text.contains("download app")) {
            signals.add(RiskSignal(ScamSignalType.APK_INSTALL_REQUEST, Confidence.HIGH, 25, "SmsIntent", "APK installation request"))
        }
        if (text.contains("anydesk") || text.contains("teamviewer") || text.contains("quicksupport") || text.contains("rustdesk")) {
            signals.add(RiskSignal(ScamSignalType.REMOTE_ACCESS_APP, Confidence.HIGH, 30, "SmsIntent", "Remote access application mentioned"))
        }
        if (text.contains("screen share") || text.contains("share screen") || text.contains("cast screen")) {
            signals.add(RiskSignal(ScamSignalType.SCREEN_SHARING_REQUEST, Confidence.HIGH, 25, "SmsIntent", "Screen sharing request"))
        }
    }

    private fun detectUrgency(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("immediately") || text.contains("urgent") || text.contains("action requir") || text.contains("act now")) {
            signals.add(RiskSignal(ScamSignalType.IMMEDIATE_ACTION, Confidence.HIGH, 10, "SmsIntent", "Urgent language detected"))
        }
        if (text.contains("within 24 hours") || text.contains("today only") || text.contains("by tonight")) {
            signals.add(RiskSignal(ScamSignalType.DEADLINE, Confidence.HIGH, 10, "SmsIntent", "Deadline mentioned"))
        }
        if (text.contains("will be disconnect") || text.contains("will be block") || text.contains("suspend")) {
            signals.add(RiskSignal(ScamSignalType.DISCONNECTION_THREAT, Confidence.HIGH, 15, "SmsIntent", "Disconnection or block threat"))
        }
        if (text.contains("legal action") || text.contains("arrest") || text.contains("fir ")) {
            signals.add(RiskSignal(ScamSignalType.LEGAL_THREAT, Confidence.HIGH, 20, "SmsIntent", "Legal threat detected"))
        }
    }

    private fun detectSocialEngineering(text: String, signals: MutableList<RiskSignal>) {
        if (text.contains("call ") && (text.contains("number") || text.contains("back") || text.contains("customer care"))) {
            signals.add(RiskSignal(ScamSignalType.CALL_THIS_NUMBER, Confidence.MEDIUM, 10, "SmsIntent", "Callback request detected"))
        }
        if (text.contains("contact agent") || text.contains("contact officer") || text.contains("support exec")) {
            signals.add(RiskSignal(ScamSignalType.CONTACT_AGENT, Confidence.HIGH, 10, "SmsIntent", "Agent contact request"))
        }
        if (text.contains("share otp") || text.contains("tell otp")) {
            signals.add(RiskSignal(ScamSignalType.SHARE_OTP, Confidence.HIGH, 25, "SmsIntent", "Request to share OTP"))
        }
        if (text.contains("share pin") || text.contains("enter pin")) {
            signals.add(RiskSignal(ScamSignalType.SHARE_PIN, Confidence.HIGH, 20, "SmsIntent", "Request to share PIN"))
        }
        if (text.contains("send money") || text.contains("pay now") || text.contains("transfer amount")) {
            signals.add(RiskSignal(ScamSignalType.SEND_MONEY, Confidence.HIGH, 15, "SmsIntent", "Request to send money"))
        }
        if (text.contains("remote support") || text.contains("helpdesk")) {
            signals.add(RiskSignal(ScamSignalType.REMOTE_SUPPORT, Confidence.MEDIUM, 15, "SmsIntent", "Remote support offer"))
        }
    }
}
