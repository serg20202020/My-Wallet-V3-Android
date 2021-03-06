package com.blockchain.swap.nabu.status

import com.blockchain.swap.nabu.datamanagers.NabuDataUserProvider
import com.blockchain.swap.nabu.models.nabu.KycTierLevel
import com.blockchain.swap.nabu.service.TierService
import io.reactivex.Single
import io.reactivex.rxkotlin.zipWith

/**
 * Class contains methods that combine both queries to user and tiers.
 */
class KycTiersQueries(
    private val nabuDataProvider: NabuDataUserProvider,
    private val tiersService: TierService
) {

    fun isKycInProgress(): Single<Boolean> =
        nabuDataProvider
            .getUser()
            .map { it.tiers?.next ?: 0 }
            .zipWith(tiersService.tiers())
            .map { (user, tiers) ->
                tiers.isNotInitialisedFor(KycTierLevel.values()[user])
            }

    fun isKycResubmissionRequired(): Single<Boolean> =
        nabuDataProvider.getUser().map { it.isMarkedForResubmission }
}
