package piuk.blockchain.android.withdraw.mvi

import com.blockchain.swap.nabu.datamanagers.CustodialWalletManager
import com.blockchain.swap.nabu.datamanagers.repositories.AssetBalancesRepository
import info.blockchain.balance.FiatValue

class WithdrawInteractor(
    private val assetBalancesRepository: AssetBalancesRepository,
    private val custodialWalletManager: CustodialWalletManager
) {

    fun fetchBalanceForCurrency(currency: String) =
        assetBalancesRepository.getActionableBalanceForAsset(currency)
            .defaultIfEmpty(FiatValue.zero(currency))

    fun fetchLinkedBanks(currency: String) =
        custodialWalletManager.getLinkedBanks().map { banks ->
            banks.filter { it.currency == currency }
        }

    fun createWithdrawOrder(
        amount: FiatValue,
        bankId: String
    ) = custodialWalletManager.createWithdrawOrder(
        amount, bankId
    )

    fun fetchWithdrawFees(currency: String) =
        custodialWalletManager.fetchWithdrawFee(currency)
}