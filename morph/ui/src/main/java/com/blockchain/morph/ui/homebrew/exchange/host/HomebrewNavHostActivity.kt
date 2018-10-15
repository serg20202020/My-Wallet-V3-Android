package com.blockchain.morph.ui.homebrew.exchange.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.annotation.StringRes
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v7.widget.Toolbar
import androidx.navigation.fragment.NavHostFragment.findNavController
import com.blockchain.morph.exchange.mvi.ChangeCryptoFromAccount
import com.blockchain.morph.exchange.mvi.ChangeCryptoToAccount
import com.blockchain.morph.exchange.service.QuoteService
import com.blockchain.morph.ui.R
import com.blockchain.morph.ui.homebrew.exchange.ExchangeFragment
import com.blockchain.morph.ui.homebrew.exchange.ExchangeModel
import com.blockchain.morph.ui.homebrew.exchange.ExchangeViewModelProvider
import com.blockchain.morph.ui.homebrew.exchange.REQUEST_CODE_CHOOSE_RECEIVING_ACCOUNT
import com.blockchain.morph.ui.homebrew.exchange.REQUEST_CODE_CHOOSE_SENDING_ACCOUNT
import com.blockchain.morph.ui.homebrew.exchange.confirmation.ExchangeConfirmationFragment
import com.blockchain.morph.ui.logging.WebsocketConnectionFailureEvent
import com.blockchain.ui.chooser.AccountChooserActivity
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import org.koin.android.architecture.ext.viewModel
import piuk.blockchain.androidcore.utils.helperfunctions.consume
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import piuk.blockchain.androidcoreui.ui.base.BaseAuthActivity
import piuk.blockchain.androidcoreui.utils.logging.Logging

class HomebrewNavHostActivity : BaseAuthActivity(), HomebrewHostActivityListener, ExchangeViewModelProvider {

    private val toolbar by unsafeLazy { findViewById<Toolbar>(R.id.toolbar_general) }
    private val navHostFragment by unsafeLazy { supportFragmentManager.findFragmentById(R.id.nav_host) }
    private val navController by unsafeLazy { findNavController(navHostFragment) }
    private val currentFragment: Fragment?
        get() = navHostFragment.childFragmentManager.findFragmentById(R.id.nav_host)

    private val defaultCurrency by unsafeLazy { intent.getStringExtra(EXTRA_DEFAULT_CURRENCY) }

    override val exchangeViewModel: ExchangeModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_homebrew_host)

        val args = ExchangeFragment.bundleArgs(defaultCurrency)
        navController.navigate(R.id.exchangeFragment, args)
    }

    override fun onSupportNavigateUp(): Boolean =
        if (currentFragment is ExchangeConfirmationFragment) {
            consume { navController.popBackStack() }
        } else {
            consume { finish() }
        }

    override fun setToolbarTitle(title: Int) {
        setupToolbar(toolbar, title)
    }

    override fun onResume() {
        super.onResume()
        newQuoteWebSocket()
    }

    override fun onPause() {
        compositeDisposable.clear()
        super.onPause()
    }

    override fun launchConfirmation() {
        navController.navigate(R.id.exchangeConfirmationFragment)
    }

    private val compositeDisposable = CompositeDisposable()

    private fun newQuoteWebSocket(): QuoteService {
        val quotesService = exchangeViewModel.quoteService

        compositeDisposable += listenForConnectionErrors(quotesService)

        compositeDisposable += quotesService.openAsDisposable()

        return quotesService
    }

    private var snackbar: Snackbar? = null

    private fun listenForConnectionErrors(quotesSocket: QuoteService) =
        quotesSocket.connectionStatus
            .map {
                it != QuoteService.Status.Error
            }
            .distinctUntilChanged()
            .subscribe {
                if (it) {
                    snackbar?.dismiss()
                } else {
                    snackbar = Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.connection_error,
                        Snackbar.LENGTH_INDEFINITE
                    ).apply {
                        show()
                    }

                    Logging.logCustom(WebsocketConnectionFailureEvent())
                }
            }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data != null) {
            val account = AccountChooserActivity.getSelectedAccount(data)
            when (requestCode) {
                REQUEST_CODE_CHOOSE_SENDING_ACCOUNT -> {
                    exchangeViewModel.inputEventSink.onNext(
                        ChangeCryptoFromAccount(account.accountReference)
                    )
                }
                REQUEST_CODE_CHOOSE_RECEIVING_ACCOUNT -> {
                    exchangeViewModel.inputEventSink.onNext(
                        ChangeCryptoToAccount(account.accountReference)
                    )
                }
                else -> throw IllegalArgumentException("Unknown request code $requestCode")
            }
        }
    }

    companion object {

        private const val EXTRA_DEFAULT_CURRENCY =
            "com.blockchain.morph.ui.homebrew.exchange.EXTRA_DEFAULT_CURRENCY"

        @JvmStatic
        fun start(context: Context, defaultCurrency: String) {
            Intent(context, HomebrewNavHostActivity::class.java).apply {
                putExtra(EXTRA_DEFAULT_CURRENCY, defaultCurrency)
            }.run { context.startActivity(this) }
        }
    }
}

internal interface HomebrewHostActivityListener {

    fun setToolbarTitle(@StringRes title: Int)

    fun launchConfirmation()
}