package piuk.blockchain.android.ui.dashboard.adapter

import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import piuk.blockchain.android.util.getColor
import piuk.blockchain.android.util.assetName
import piuk.blockchain.android.util.setCoinIcon
import com.blockchain.preferences.CurrencyPrefs
import kotlinx.android.synthetic.main.item_dashboard_asset_card.view.*
import piuk.blockchain.android.ui.adapters.AdapterDelegate
import piuk.blockchain.android.ui.dashboard.CryptoAssetState
import piuk.blockchain.androidcoreui.utils.extensions.inflate
import piuk.blockchain.androidcoreui.utils.extensions.invisible
import com.robinhood.spark.SparkAdapter
import info.blockchain.balance.CryptoCurrency
import kotlinx.android.synthetic.main.item_dashboard_asset_card.view.cardLayout
import piuk.blockchain.android.R
import piuk.blockchain.android.ui.dashboard.asDeltaPercent
import piuk.blockchain.android.ui.dashboard.format
import piuk.blockchain.android.ui.dashboard.showLoading
import piuk.blockchain.androidcoreui.utils.extensions.gone
import piuk.blockchain.androidcoreui.utils.extensions.setOnClickListenerDebounced
import piuk.blockchain.androidcoreui.utils.extensions.visible

// Uses sparkline lib from here: https://github.com/robinhood/spark

class AssetCardDelegate<in T>(
    private val prefs: CurrencyPrefs,
    private val onCardClicked: (CryptoCurrency) -> Unit
) : AdapterDelegate<T> {

    override fun isForViewType(items: List<T>, position: Int): Boolean =
        items[position] is CryptoAssetState

    override fun onCreateViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
        AssetCardViewHolder(parent.inflate(R.layout.item_dashboard_asset_card))

    override fun onBindViewHolder(
        items: List<T>,
        position: Int,
        holder: RecyclerView.ViewHolder
    ) = (holder as AssetCardViewHolder).bind(
        items[position] as CryptoAssetState,
        prefs.selectedFiatCurrency,
        onCardClicked
    )
}

private class AssetCardViewHolder(
    itemView: View
) : RecyclerView.ViewHolder(itemView) {

    internal fun bind(state: CryptoAssetState, fiatSymbol: String, onCardClicked: (CryptoCurrency) -> Unit) {
        with(itemView) {
            icon.setCoinIcon(state.currency)
            currency.setText(state.currency.assetName())
        }

        when {
            state.hasBalanceError -> renderError(state)
            state.isLoading -> renderLoading()
            else -> renderLoaded(state, fiatSymbol, onCardClicked)
        }
    }

    private fun renderLoading() {
        with(itemView) {
            cardLayout.isEnabled = false
            setOnClickListener { }

            showContent()

            fiat_balance.showLoading()
            crypto_balance.showLoading()
            price.showLoading()
            price_delta.showLoading()
            price_delta_interval.showLoading()
            sparkview.invisible()
        }
    }

    private fun renderLoaded(state: CryptoAssetState, fiatSymbol: String, onCardClicked: (CryptoCurrency) -> Unit) {
        with(itemView) {
            cardLayout.isEnabled = true
            setOnClickListenerDebounced { onCardClicked(state.currency) }

            showContent()

            fiat_balance.text = state.fiatBalance.format(fiatSymbol)
            crypto_balance.text = state.balance.format(state.currency)

            price.text = state.price?.price().format(fiatSymbol)

            price_delta.asDeltaPercent(state.priceDelta)
            price_delta_interval.text = context.getString(R.string.asset_card_rate_period)

            if (state.priceTrend.isNotEmpty()) {
                sparkview.lineColor = state.currency.getColor(context)
                sparkview.adapter = PriceAdapter(state.priceTrend.toFloatArray())
                sparkview.visible()
            } else {
                sparkview.gone()
            }
        }
    }

    private fun renderError(state: CryptoAssetState) {
        showError()

        with(itemView) {
            cardLayout.isEnabled = false
            setOnClickListener { }

            val text = resources.getString(R.string.dashboard_asset_error, state.currency.displayTicker)
            error_msg.text = text
        }
    }

    private fun showContent() {
        with(itemView) {
            fiat_balance.visible()
            crypto_balance.visible()
            sparkview.visible()
            separator.visible()
            price.visible()
            price_delta.visible()
            price_delta_interval.visible()

            error_msg.invisible()
        }
    }

    private fun showError() {
        with(itemView) {
            fiat_balance.invisible()
            crypto_balance.invisible()
            sparkview.invisible()
            separator.invisible()
            price.invisible()
            price_delta.invisible()
            price_delta_interval.invisible()

            error_msg.visible()
        }
    }
}

private class PriceAdapter(private val yData: FloatArray) : SparkAdapter() {
    override fun getCount(): Int = yData.size
    override fun getItem(index: Int): Any = yData[index]
    override fun getY(index: Int): Float = yData[index]
}
