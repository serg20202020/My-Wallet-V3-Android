package piuk.blockchain.android.ui.buysell.overview

import android.support.annotation.StringRes
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import io.reactivex.rxkotlin.subscribeBy
import piuk.blockchain.android.R
import piuk.blockchain.android.ui.buysell.details.models.AwaitingFundsModel
import piuk.blockchain.android.ui.buysell.details.models.BuySellDetailsModel
import piuk.blockchain.android.ui.buysell.details.models.RecurringTradeDisplayModel
import piuk.blockchain.android.ui.buysell.overview.models.BuySellButtons
import piuk.blockchain.android.ui.buysell.overview.models.BuySellDisplayable
import piuk.blockchain.android.ui.buysell.overview.models.BuySellTransaction
import piuk.blockchain.android.ui.buysell.overview.models.EmptyTransactionList
import piuk.blockchain.android.ui.buysell.overview.models.KycInProgress
import piuk.blockchain.android.ui.buysell.overview.models.RecurringBuyOrder
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.android.util.extensions.addToCompositeDisposable
import piuk.blockchain.android.util.extensions.toFormattedString
import piuk.blockchain.androidbuysell.datamanagers.CoinifyDataManager
import piuk.blockchain.androidbuysell.models.TradeData
import piuk.blockchain.androidbuysell.models.coinify.BankDetails
import piuk.blockchain.androidbuysell.models.coinify.BuyFrequency
import piuk.blockchain.androidbuysell.models.coinify.CoinifyTrade
import piuk.blockchain.androidbuysell.models.coinify.KycResponse
import piuk.blockchain.androidbuysell.models.coinify.Medium
import piuk.blockchain.androidbuysell.models.coinify.ReviewState
import piuk.blockchain.androidbuysell.models.coinify.Subscription
import piuk.blockchain.androidbuysell.models.coinify.TradeState
import piuk.blockchain.androidbuysell.services.ExchangeService
import piuk.blockchain.androidbuysell.utils.fromIso8601
import piuk.blockchain.androidcore.data.currency.CurrencyFormatUtil
import piuk.blockchain.androidcore.data.metadata.MetadataManager
import piuk.blockchain.androidcore.utils.extensions.applySchedulers
import piuk.blockchain.androidcore.utils.extensions.toSerialisedString
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import piuk.blockchain.androidcoreui.ui.base.BasePresenter
import timber.log.Timber
import java.math.RoundingMode
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class CoinifyOverviewPresenter @Inject constructor(
    private val exchangeService: ExchangeService,
    private val coinifyDataManager: CoinifyDataManager,
    private val metadataManager: MetadataManager,
    private val stringUtils: StringUtils,
    private val currencyFormatUtil: CurrencyFormatUtil
) : BasePresenter<CoinifyOverviewView>() {

    // Display States
    private val buttons = BuySellButtons()
    private val kycInReview = KycInProgress()
    private val empty = EmptyTransactionList()
    // Display List
    private val displayList: MutableList<BuySellDisplayable> = mutableListOf(buttons)
    // Observables
    private val tokenObservable: Observable<String>
        get() = exchangeService.getExchangeMetaData()
            .addToCompositeDisposable(this)
            .applySchedulers()
            .map { it.coinify!!.token }

    private val tradesObservable: Observable<CoinifyTrade>
        get() = tokenObservable
            .flatMap { coinifyDataManager.getTrades(it) }

    private val kycReviewsObservable: Observable<Boolean> by unsafeLazy {
        tokenObservable
            .flatMapSingle { coinifyDataManager.getKycReviews(it) }
            .map { it.hasPendingKyc() }
            .cache()
    }

    private val recurringBuySingle: Single<List<Subscription>> by unsafeLazy {
        tokenObservable
            .flatMap { coinifyDataManager.getSubscriptions(it) }
            .filter { it.isActive }
            .toList()
            .cache()
    }

    override fun onViewReady() {
        renderTrades(emptyList())
        view.renderViewState(OverViewState.Loading)
        checkKycStatus()
        checkSubscriptionStatus()
    }

    internal fun refreshTransactionList() {
        tradesObservable
            .toList()
            .doOnSuccess { updateMetadataAsNeeded(it) }
            .toObservable()
            .flatMapIterable { it }
            .map { mapTradeToDisplayObject(it) }
            .toList()
            .doOnError { Timber.e(it) }
            .subscribeBy(
                onSuccess = { renderTrades(it) },
                onError = {
                    view.renderViewState(OverViewState.Failure(R.string.buy_sell_overview_error_loading_transactions))
                }
            )
    }

    internal fun onBuySelected() {
        kycReviewsObservable
            .doOnSubscribe { view.displayProgressDialog() }
            .doAfterTerminate { view.dismissProgressDialog() }
            .subscribeBy(
                onNext = { hasPendingKyc ->
                    if (hasPendingKyc) {
                        view.launchCardBuyFlow()
                    } else {
                        view.launchBuyPaymentSelectionFlow()
                    }
                },
                onError = {
                    view.renderViewState(OverViewState.Failure(R.string.unexpected_error))
                }
            )
    }

    internal fun onSellSelected() {
        kycReviewsObservable
            .doOnSubscribe { view.displayProgressDialog() }
            .doAfterTerminate { view.dismissProgressDialog() }
            .subscribeBy(
                onNext = { hasPendingKyc ->
                    if (hasPendingKyc) {
                        view.showAlertDialog(R.string.buy_sell_overview_sell_unavailable)
                    } else {
                        view.launchSellFlow()
                    }
                },
                onError = {
                    view.renderViewState(OverViewState.Failure(R.string.unexpected_error))
                }
            )
    }

    internal fun onTransactionSelected(transactionId: Int) {
        tradesObservable
            .doOnSubscribe { view.displayProgressDialog() }
            .filter { it.id == transactionId }
            .firstOrError()
            .doOnEvent { _, _ -> view.dismissProgressDialog() }
            .subscribeBy(
                onSuccess = {
                    if (it.isAwaitingTransferIn()) {
                        view.launchAwaitingTransferPage(getAwaitingFundsModel(it))
                    } else {
                        view.launchDetailsPage(getBuySellDetailsModel(it))
                    }
                },
                onError = {
                    view.renderViewState(OverViewState.Failure(R.string.buy_sell_overview_error_loading_transactions))
                }
            )
    }

    internal fun onSubscriptionClicked(subscriptionId: Int) {
        Single.zip(
            recurringBuySingle,
            tradesObservable
                .filter { it.tradeSubscriptionId == subscriptionId }
                .firstOrError(),
            BiFunction { subscriptions: List<Subscription>, trade: CoinifyTrade ->
                return@BiFunction subscriptions.first { it.id == subscriptionId } to trade
            }
        ).doOnSubscribe { view.displayProgressDialog() }
            .doAfterTerminate { view.dismissProgressDialog() }
            .subscribeBy(
                onSuccess = {
                    val subscription = it.first
                    val trade = it.second

                    val calendar = Calendar.getInstance()
                        .apply { time = trade.createTime.fromIso8601()!! }
                    val dayOfWeek = calendar.getDisplayName(
                        Calendar.DAY_OF_WEEK,
                        Calendar.LONG,
                        Locale.getDefault()
                    )
                    val dayOfMonth = calendar.getDisplayName(
                        Calendar.DAY_OF_WEEK_IN_MONTH,
                        Calendar.LONG,
                        Locale.getDefault()
                    )
                    val frequencyString = when (subscription.frequency) {
                        BuyFrequency.Daily -> stringUtils.getString(R.string.buy_sell_recurring_frequency_daily)
                        BuyFrequency.Weekly -> stringUtils.getFormattedString(
                            R.string.buy_sell_recurring_frequency_weekly,
                            dayOfWeek
                        )
                        BuyFrequency.Monthly -> stringUtils.getFormattedString(
                            R.string.buy_sell_recurring_frequency_monthly,
                            dayOfMonth
                        )
                    }

                    val amount = trade.inAmount
                    val fee = trade.transferIn.getFee().toBigDecimal()
                        .setScale(2, RoundingMode.UP).toPlainString()
                    val currency = trade.transferIn.currency.toUpperCase()

                    var dateString =
                        subscription.endTime?.fromIso8601()?.toFormattedString(
                            view.locale
                        )
                    if (dateString != null) {
                        dateString += "${stringUtils.getString(R.string.buy_sell_recurring_order_duration_until)} " +
                            "$dateString"
                    }
                    val displayModel = RecurringTradeDisplayModel(
                        amountString = stringUtils.getFormattedString(
                            R.string.buy_sell_recurring_order_amount,
                            amount,
                            currency,
                            currency,
                            fee
                        ),
                        frequencyString = frequencyString,
                        durationStringToFormat = stringUtils.getString(
                            R.string.buy_sell_recurring_order_duration_until_cancelled
                        ),
                        duration = dateString
                            ?: stringUtils.getString(R.string.buy_sell_recurring_order_duration_you_cancelled)
                    )

                    view.launchRecurringTradeDetail(displayModel)
                },
                onError = { Timber.e(it) }
            )
    }

    private fun getAwaitingFundsModel(coinifyTrade: CoinifyTrade): AwaitingFundsModel {
        val (referenceText, account, bank, holder, _, _) = coinifyTrade.transferIn.details as BankDetails
        val formattedAmount = currencyFormatUtil.formatFiatWithSymbol(
            coinifyTrade.transferIn.sendAmount,
            coinifyTrade.transferIn.currency,
            view.locale
        )

        return AwaitingFundsModel(
            coinifyTrade.id,
            formattedAmount,
            referenceText,
            holder.name,
            holder.address.getFormattedAddressString(),
            account.number,
            account.bic,
            "${bank.name}, ${bank.address.getFormattedAddressString()}"
        )
    }

    private fun checkKycStatus() {
        kycReviewsObservable
            .subscribeBy(
                onNext = { hasPendingKyc ->
                    if (hasPendingKyc) {
                        displayList.add(0, kycInReview)
                        view.renderViewState(OverViewState.Data(displayList.toList()))
                    }
                },
                onError = { Timber.e(it) }
            )
    }

    private fun checkSubscriptionStatus() {
        Single.zip(
            recurringBuySingle,
            tradesObservable
                .filter { it.tradeSubscriptionId != null }
                .toList(),
            // Returns a pair of subscription objects with the first trade created by the subscription
            // This is so that we can calculate the start date
            BiFunction { subscriptions: List<Subscription>, trades: List<CoinifyTrade> ->
                val list = mutableListOf<Pair<Subscription, CoinifyTrade>>()
                for (sub in subscriptions) {
                    val coinifyTrade = trades.firstOrNull { sub.id == it.tradeSubscriptionId }
                    if (coinifyTrade != null) {
                        list.add(sub to coinifyTrade)
                    }
                }

                return@BiFunction list.toList()
            }
        ).subscribeBy(
            onSuccess = {
                if (!it.isEmpty()) {
                    it.map {
                        val subscription = it.first
                        val trade = it.second

                        val calendar = Calendar.getInstance()
                            .apply { time = trade.createTime.fromIso8601()!! }
                        val dayOfWeek = calendar.getDisplayName(
                            Calendar.DAY_OF_WEEK,
                            Calendar.LONG,
                            Locale.getDefault()
                        )
                        val dayOfMonth = calendar.getDisplayName(
                            Calendar.DAY_OF_WEEK_IN_MONTH,
                            Calendar.LONG,
                            Locale.getDefault()
                        )
                        val displayString = when (subscription.frequency) {
                            BuyFrequency.Daily -> stringUtils.getString(R.string.buy_sell_overview_subscription_daily)
                            BuyFrequency.Weekly -> stringUtils.getFormattedString(
                                R.string.buy_sell_overview_subscription_weekly,
                                dayOfWeek
                            )
                            BuyFrequency.Monthly -> stringUtils.getFormattedString(
                                R.string.buy_sell_overview_subscription_monthly,
                                dayOfMonth
                            )
                        }

                        return@map RecurringBuyOrder(displayString, subscription.id)
                    }.run { displayList.addAll(1, this) }
                    view.renderViewState(OverViewState.Data(displayList.toList()))
                }
            },
            onError = { Timber.e(it) }
        )
    }

    private fun renderTrades(trades: List<BuySellTransaction>) {
        displayList.removeAll { it is BuySellTransaction || it is EmptyTransactionList }
        displayList.apply { addAll(trades) }
            .apply {
                if (trades.isEmpty()) {
                    add(empty)
                } else {
                    removeAll { it is EmptyTransactionList }
                }
            }
        view.renderViewState(OverViewState.Data(displayList.toList()))
    }

    @StringRes
    private fun tradeStateToStringRes(state: TradeState): Int = when (state) {
        TradeState.AwaitingTransferIn -> R.string.buy_sell_state_awaiting_funds
        TradeState.Completed, TradeState.CompletedTest -> R.string.buy_sell_state_completed
        TradeState.Cancelled -> R.string.buy_sell_state_cancelled
        TradeState.Rejected -> R.string.buy_sell_state_rejected
        TradeState.Expired -> R.string.buy_sell_state_expired
        TradeState.Processing, TradeState.Reviewing -> R.string.buy_sell_state_processing
    }

    private fun updateMetadataAsNeeded(trades: List<CoinifyTrade>) {
        exchangeService.getExchangeMetaData()
            .map {
                val list = it.coinify!!.trades ?: mutableListOf()
                for (tradeData in list) {
                    val coinifyTrade = trades.firstOrNull { it.id == tradeData.id }
                    // Here we update the stored metadata state if necessary
                    if (coinifyTrade != null && tradeData.state != coinifyTrade.state.toString()) {
                        tradeData.state = coinifyTrade.state.toString()
                    }
                }
                // Here we remove any transactions that are failed from metadata, as we aren't interested in them
                list.removeAll { it.isFailureState() }
                it.coinify!!.trades = list
                return@map it
            }
            .flatMapCompletable {
                metadataManager.saveToMetadata(
                    it.toSerialisedString(),
                    ExchangeService.METADATA_TYPE_EXCHANGE
                )
            }
            .subscribeBy(onError = { Timber.e(it) })
    }

    // region Model helper functions
    private fun mapTradeToDisplayObject(coinifyTrade: CoinifyTrade): BuySellTransaction {
        val displayString = if (coinifyTrade.isSellTransaction()) {
            "-${coinifyTrade.inAmount} ${coinifyTrade.inCurrency.capitalize()}"
        } else {
            val amount = coinifyTrade.outAmount ?: coinifyTrade.outAmountExpected
            "+$amount ${coinifyTrade.outCurrency.capitalize()}"
        }

        return BuySellTransaction(
            transactionId = coinifyTrade.id,
            time = coinifyTrade.createTime.fromIso8601()!!,
            displayAmount = displayString,
            tradeStateString = tradeStateToStringRes(coinifyTrade.state),
            tradeState = coinifyTrade.state,
            isSellTransaction = coinifyTrade.isSellTransaction()
        )
    }

    private fun getBuySellDetailsModel(coinifyTrade: CoinifyTrade): BuySellDetailsModel {
        // Title
        val stateString = stringUtils.getString(tradeStateToStringRes(coinifyTrade.state))
        val titleStringRes =
            if (coinifyTrade.isSellTransaction()) {
                R.string.buy_sell_detail_title_sell
            } else {
                R.string.buy_sell_detail_title_buy
            }
        val titleString = stringUtils.getFormattedString(titleStringRes, stateString)
        // Date
        val time = coinifyTrade.updateTime.fromIso8601() ?: Date()
        val calendar = Calendar.getInstance()
        val timeZone = calendar.timeZone
        val offset = timeZone.getOffset(time.time)
        val offsetTime = time.time + offset
        val dateString = Date(offsetTime).toFormattedString(view.locale)
        // Amounts
        val sent = coinifyTrade.transferIn.receiveAmount
        val sentWithFee = coinifyTrade.transferIn.sendAmount
        val received = coinifyTrade.transferOut.sendAmount
        val sellPaymentFee = coinifyTrade.transferOut.getFee()
        val paymentFee = coinifyTrade.transferIn.getFee().toBigDecimal()
            .setScale(8, RoundingMode.HALF_UP)
            .abs()
            .stripTrailingZeros()
        // Currency
        val receiveCurrency = coinifyTrade.transferOut.currency.capitalize()
        val sendCurrency = coinifyTrade.transferIn.currency.capitalize()
        val isEndState = coinifyTrade.state.isEndState()
        // Model Strings
        val headlineAmount: String
        val detailAmount: String
        val paymentFeeString: String
        val exchangeRateString: String
        val receiveTitleString: String
        val amountString: String
        val totalString: String

        if (!coinifyTrade.isSellTransaction()) {
            // Crypto out (from Coinify's perspective)
            headlineAmount = "$received $receiveCurrency"
            detailAmount = "$received $receiveCurrency"
            // Exchange rate (always in fiat)
            val exchangeRate = sent / received
            exchangeRateString = currencyFormatUtil.formatFiatWithSymbol(exchangeRate, sendCurrency, view.locale)
            // Fiat in
            amountString = currencyFormatUtil.formatFiatWithSymbol(sent, sendCurrency, view.locale)
            paymentFeeString = currencyFormatUtil.formatFiatWithSymbol(paymentFee.toDouble(), sendCurrency, view.locale)
            totalString = currencyFormatUtil.formatFiatWithSymbol(sentWithFee, sendCurrency, view.locale)
            // Received/Sold title
            receiveTitleString = getReceiveTitleString(
                isEndState,
                R.string.buy_sell_detail_currency_to_be_received,
                R.string.buy_sell_detail_currency_received,
                receiveCurrency
            )
        } else {
            // Fiat out (from Coinify's perspective)
            headlineAmount =
                currencyFormatUtil.formatFiatWithSymbol(received - sellPaymentFee, receiveCurrency, view.locale)
            detailAmount = "$sent $sendCurrency"
            // Exchange rate (always in fiat)
            val exchangeRate = received / sent
            exchangeRateString = currencyFormatUtil.formatFiatWithSymbol(exchangeRate, receiveCurrency, view.locale)
            // Crypto in
            paymentFeeString = "Not rendered"
            totalString = "Not rendered"
            amountString = "Not rendered"
            // Received/Sold title
            receiveTitleString = getReceiveTitleString(
                isEndState,
                R.string.buy_sell_detail_currency_to_be_sold,
                R.string.buy_sell_detail_currency_sold,
                sendCurrency
            )
        }

        return BuySellDetailsModel(
            coinifyTrade.isSellTransaction(),
            coinifyTrade.isAwaitingCardPayment(),
            titleString,
            headlineAmount,
            detailAmount,
            dateString,
            "#${coinifyTrade.id}",
            coinifyTrade.id,
            receiveTitleString,
            exchangeRateString,
            amountString,
            paymentFeeString,
            totalString
        )
    }
    // endregion

    private fun getReceiveTitleString(
        isEndState: Boolean,
        @StringRes pendingString: Int,
        @StringRes completeString: Int,
        currencyCode: String
    ): String = stringUtils.getFormattedString(
        if (isEndState) completeString else pendingString,
        currencyCode.capitalize()
    )
    // endregion

    // region Extension functions
    private fun List<KycResponse>.hasPendingKyc(): Boolean =
        this.any { it.state.isProcessing() } &&
            this.none { it.state == ReviewState.Completed }

    private fun CoinifyTrade.isAwaitingTransferIn(): Boolean =
        (!this.isSellTransaction() &&
            this.state == TradeState.AwaitingTransferIn &&
            this.transferIn.medium == Medium.Bank)

    private fun CoinifyTrade.isAwaitingCardPayment(): Boolean =
        (!this.isSellTransaction() &&
            this.state == TradeState.AwaitingTransferIn &&
            this.transferIn.medium == Medium.Card)

    /**
     * See https://github.com/blockchain/bitcoin-exchange-client/blob/master/src/trade.js#L318
     */
    private fun TradeData.isFailureState(): Boolean =
        this.state == "cancelled" || this.state == "rejected" || this.state == "expired"
    // endregion
}

sealed class OverViewState {

    object Loading : OverViewState()
    class Failure(@StringRes val message: Int) : OverViewState()
    class Data(val items: List<BuySellDisplayable>) : OverViewState()
}