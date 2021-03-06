package piuk.blockchain.android.ui.kyc.address

import com.blockchain.swap.nabu.NabuToken
import com.blockchain.swap.nabu.datamanagers.NabuDataManager
import io.reactivex.Single

internal class Tier2DecisionAdapter(
    private val nabuToken: NabuToken,
    private val nabuDataManager: NabuDataManager
) : Tier2Decision {

    override fun progressToTier2(): Single<Tier2Decision.NextStep> =
        nabuToken.fetchNabuToken()
            .flatMap(nabuDataManager::getUser)
            .map { user ->
                if (user.tierInProgressOrCurrentTier == 1) {
                    Tier2Decision.NextStep.Tier1Complete
                } else {
                    val tiers = user.tiers
                    if (tiers == null || tiers.next ?: 0 > tiers.selected ?: 0) {
                        // the backend is telling us the user should be put down path for tier2 even though they
                        // selected tier 1, so we need to inform them
                        Tier2Decision.NextStep.Tier2ContinueTier1NeedsMoreInfo
                    } else {
                        Tier2Decision.NextStep.Tier2Continue
                    }
                }
            }
}
