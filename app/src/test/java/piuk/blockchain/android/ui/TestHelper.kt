package piuk.blockchain.android.ui

import com.blockchain.swap.nabu.models.nabu.KycState
import com.blockchain.swap.nabu.models.nabu.KycTierState
import com.blockchain.swap.nabu.models.nabu.LimitsJson
import com.blockchain.swap.nabu.models.nabu.NabuUser
import com.blockchain.swap.nabu.models.nabu.TierResponse
import com.blockchain.swap.nabu.models.nabu.KycTiers
import com.blockchain.swap.nabu.models.nabu.UserState
import com.blockchain.swap.nabu.metadata.NabuCredentialsMetadata
import com.blockchain.swap.nabu.models.tokenresponse.NabuOfflineTokenResponse

fun getBlankNabuUser(kycState: KycState = KycState.None): NabuUser = NabuUser(
    firstName = "",
    lastName = "",
    email = "",
    emailVerified = false,
    dob = null,
    mobile = "",
    mobileVerified = false,
    address = null,
    state = UserState.None,
    kycState = kycState,
    insertedAt = "",
    updatedAt = ""
)

val validOfflineTokenMetadata get() = NabuCredentialsMetadata("userId", "lifetimeToken")
val validOfflineToken
    get() = NabuOfflineTokenResponse("userId",
        "lifetimeToken")

fun tiers(tier1State: KycTierState, tier2State: KycTierState): KycTiers {
    return KycTiers(
        tiersResponse = listOf(
            TierResponse(
                0,
                "Tier 0",
                state = KycTierState.Verified,
                limits = LimitsJson(
                    currency = "USD",
                    daily = null,
                    annual = null
                )
            ),
            TierResponse(
                1,
                "Tier 1",
                state = tier1State,
                limits = LimitsJson(
                    currency = "USD",
                    daily = null,
                    annual = 1000.0.toBigDecimal()
                )
            ),
            TierResponse(
                2,
                "Tier 2",
                state = tier2State,
                limits = LimitsJson(
                    currency = "USD",
                    daily = 25000.0.toBigDecimal(),
                    annual = null
                )
            )
        )
    )
}