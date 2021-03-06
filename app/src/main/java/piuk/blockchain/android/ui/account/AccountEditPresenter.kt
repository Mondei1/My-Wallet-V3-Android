package piuk.blockchain.android.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import com.blockchain.notifications.analytics.AddressAnalytics
import com.blockchain.notifications.analytics.Analytics
import com.blockchain.notifications.analytics.WalletAnalytics
import com.blockchain.remoteconfig.CoinSelectionRemoteConfig
import com.google.zxing.WriterException
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.wallet.BitcoinCashWallet
import info.blockchain.wallet.coin.GenericMetadataAccount
import info.blockchain.wallet.payload.data.Account
import info.blockchain.wallet.payload.data.LegacyAddress
import info.blockchain.wallet.payload.data.archive
import info.blockchain.wallet.payload.data.isArchived
import info.blockchain.wallet.payload.data.unarchive
import info.blockchain.wallet.payment.Payment
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import org.bitcoinj.core.ECKey
import piuk.blockchain.android.R
import piuk.blockchain.android.data.cache.DynamicFeeCache
import piuk.blockchain.android.ui.account.AccountEditActivity.Companion.EXTRA_ACCOUNT_INDEX
import piuk.blockchain.android.ui.account.AccountEditActivity.Companion.EXTRA_ADDRESS_INDEX
import piuk.blockchain.android.ui.account.AccountEditActivity.Companion.EXTRA_CRYPTOCURRENCY
import piuk.blockchain.android.ui.swipetoreceive.SwipeToReceiveHelper
import piuk.blockchain.android.scan.QRCodeEncoder
import piuk.blockchain.android.util.LabelUtil
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.androidcore.data.bitcoincash.BchDataManager
import piuk.blockchain.androidcore.data.exchangerate.ExchangeRateDataManager
import piuk.blockchain.androidcore.data.metadata.MetadataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.data.payments.SendDataManager
import piuk.blockchain.androidcore.utils.PersistentPrefs
import piuk.blockchain.androidcore.utils.extensions.emptySubscribe
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import piuk.blockchain.androidcoreui.ui.base.BasePresenter
import piuk.blockchain.androidcoreui.ui.customviews.ToastCustom
import timber.log.Timber
import java.math.BigInteger

// TODO: This page is pretty nasty and could do with a proper refactor
class AccountEditPresenter constructor(
    private val prefs: PersistentPrefs,
    private val stringUtils: StringUtils,
    private val payloadDataManager: PayloadDataManager,
    private val bchDataManager: BchDataManager,
    private val metadataManager: MetadataManager,
    private val sendDataManager: SendDataManager,
    private val swipeToReceiveHelper: SwipeToReceiveHelper,
    private val dynamicFeeCache: DynamicFeeCache,
    private val analytics: Analytics,
    private val exchangeRates: ExchangeRateDataManager,
    private val coinSelectionRemoteConfig: CoinSelectionRemoteConfig
) : BasePresenter<AccountEditView>() {

    // Visible for data binding
    internal lateinit var accountModel: AccountEditModel

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var legacyAddress: LegacyAddress? = null
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var account: Account? = null
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var bchAccount: GenericMetadataAccount? = null
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var secondPassword: String? = null
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var pendingTransaction: PendingTransaction? = null

    private var accountIndex: Int = 0
    private val cryptoCurrency: CryptoCurrency by unsafeLazy {
        view.activityIntent.getSerializableExtra(EXTRA_CRYPTOCURRENCY) as CryptoCurrency
    }

    override fun onViewReady() {
        val intent = view.activityIntent

        accountIndex = intent.getIntExtra(EXTRA_ACCOUNT_INDEX, -1)
        val addressIndex = intent.getIntExtra(EXTRA_ADDRESS_INDEX, -1)

        check(accountIndex >= 0 || addressIndex >= 0) { "Both accountIndex and addressIndex are less than 0" }
        check(cryptoCurrency != CryptoCurrency.ETHER) { "Ether is not supported on this page" }

        if (cryptoCurrency == CryptoCurrency.BTC) {
            renderBtc(accountIndex, addressIndex)
        } else {
            renderBch(accountIndex)
        }
    }

    private fun renderBtc(accountIndex: Int, addressIndex: Int) {
        if (accountIndex >= 0) {
            if (accountIndex >= payloadDataManager.accounts.size) {
                view.showToast(R.string.unexpected_error, ToastCustom.TYPE_ERROR)
                view.finishPage()
                return
            }
            // V3
            account = payloadDataManager.accounts[accountIndex]
            with(accountModel) {
                label = account!!.label
                labelHeader = stringUtils.getString(R.string.name)
                xpubDescriptionVisibility = View.VISIBLE
                xpubText = stringUtils.getString(R.string.extended_public_key)
                transferFundsVisibility = View.GONE
                updateArchivedUi(account!!.isArchived, ::isArchivableBtc)
                setDefault(isDefaultBtc(account))
            }
        } else if (addressIndex >= 0) {
            if (addressIndex >= payloadDataManager.legacyAddresses.size) {
                view.showToast(R.string.unexpected_error, ToastCustom.TYPE_ERROR)
                view.finishPage()
                return
            }
            // V2
            legacyAddress = payloadDataManager.legacyAddresses[addressIndex]
            var label: String? = legacyAddress!!.label
            if (label.isNullOrEmpty()) {
                label = legacyAddress!!.address
            }
            with(accountModel) {
                this.label = label
                labelHeader = stringUtils.getString(R.string.name)
                xpubDescriptionVisibility = View.GONE
                xpubText = stringUtils.getString(R.string.address)
                defaultAccountVisibility = View.GONE // No default for V2
                updateArchivedUi(
                    legacyAddress!!.isArchived,
                    ::isArchivableBtc
                )

                archiveVisibility = View.VISIBLE
            }

            // Subtract fee
            val balanceAfterFee = payloadDataManager.getAddressBalance(
                legacyAddress!!.address
            ).toBigInteger().toLong() - sendDataManager.estimatedFee(
                1, 1,
                BigInteger.valueOf(dynamicFeeCache.btcFeeOptions!!.regularFee * 1000)
            ).toLong()

            if (balanceAfterFee > Payment.DUST.toLong()) {
                accountModel.transferFundsVisibility = View.VISIBLE
            } else {
                // No need to show 'transfer' if funds are less than dust amount
                accountModel.transferFundsVisibility = View.GONE
            }
        }
    }

    private fun renderBch(accountIndex: Int) {
        val accountMetadataList = bchDataManager.getAccountMetadataList()
        if (accountIndex >= accountMetadataList.size) {
            view.showToast(R.string.unexpected_error, ToastCustom.TYPE_ERROR)
            view.finishPage()
            return
        }

        bchAccount = accountMetadataList[accountIndex]
        with(accountModel) {
            label = bchAccount!!.label
            labelHeader = stringUtils.getString(R.string.name)
            xpubDescriptionVisibility = View.VISIBLE
            xpubText = stringUtils.getString(R.string.extended_public_key)
            transferFundsVisibility = View.GONE
            updateArchivedUi(bchAccount!!.isArchived, ::isArchivableBch)
            setDefault(isDefaultBch(bchAccount))
        }

        view.hideMerchantCopy()
    }

    private fun setDefault(isDefault: Boolean) {
        if (isDefault) {
            with(accountModel) {
                defaultAccountVisibility = View.GONE
                archiveAlpha = 0.5f
                archiveText = stringUtils.getString(R.string.default_account_description)
                archiveClickable = false
            }
        } else {
            with(accountModel) {
                defaultAccountVisibility = View.VISIBLE
                defaultText = stringUtils.getString(R.string.make_default)
                defaultTextColor = R.color.primary_blue_accent
            }
        }
    }

    private fun isDefaultBtc(account: Account?): Boolean =
        payloadDataManager.defaultAccount === account

    private fun isDefaultBch(account: GenericMetadataAccount?): Boolean =
        bchDataManager.getDefaultGenericMetadataAccount() === account

    private fun updateArchivedUi(isArchived: Boolean, archivable: () -> Boolean) {
        if (isArchived) {
            with(accountModel) {
                archiveHeader = stringUtils.getString(R.string.unarchive)
                archiveText = stringUtils.getString(R.string.archived_description)
                archiveAlpha = 1.0f
                archiveVisibility = View.VISIBLE
                archiveClickable = true

                labelAlpha = 0.5f
                xpubAlpha = 0.5f
                defaultAlpha = 0.5f
                transferFundsAlpha = 0.5f
                labelClickable = false
                xpubClickable = false
                defaultClickable = false
                transferFundsClickable = false
            }
        } else {
            // Don't allow archiving of default account
            if (archivable()) {
                with(accountModel) {
                    archiveAlpha = 1.0f
                    archiveVisibility = View.VISIBLE
                    archiveText = stringUtils.getString(R.string.not_archived_description)
                    archiveClickable = true
                }
            } else {
                with(accountModel) {
                    archiveVisibility = View.VISIBLE
                    archiveAlpha = 0.5f
                    archiveText = stringUtils.getString(R.string.default_account_description)
                    archiveClickable = false
                }
            }

            with(accountModel) {
                archiveHeader = stringUtils.getString(R.string.archive)
                labelAlpha = 1.0f
                xpubAlpha = 1.0f
                defaultAlpha = 1.0f
                transferFundsAlpha = 1.0f
                labelClickable = true
                xpubClickable = true
                defaultClickable = true
                transferFundsClickable = true
            }
        }
    }

    @SuppressLint("CheckResult")
    internal fun onClickTransferFunds() {
        compositeDisposable += getPendingTransactionForLegacyAddress(cryptoCurrency, legacyAddress)
            .doOnSubscribe { view.showProgressDialog(R.string.please_wait) }
            .doAfterTerminate { view.dismissProgressDialog() }
            .doOnError { Timber.e(it) }
            .doOnNext { pendingTransaction = it }
            .subscribe(
                { pendingTransaction ->
                    if (pendingTransaction != null && pendingTransaction.bigIntAmount.compareTo(BigInteger.ZERO) == 1) {
                        val details = getTransactionDetailsForDisplay(pendingTransaction)
                        view.showPaymentDetails(details)
                    } else {
                        view.showToast(R.string.insufficient_funds, ToastCustom.TYPE_ERROR)
                    }
                },
                {
                    view.showToast(R.string.insufficient_funds, ToastCustom.TYPE_ERROR)
                }
            )
    }

    internal fun transferFundsClickable(): Boolean = accountModel.transferFundsClickable

    private fun getTransactionDetailsForDisplay(pendingTransaction: PendingTransaction): PaymentConfirmationDetails {

        val destination = if (pendingTransaction.receivingObject?.label?.isEmpty() == true) {
            pendingTransaction.receivingAddress
        } else {
            pendingTransaction.receivingObject?.label ?: ""
        }

        val fiatCurrency = prefs.selectedFiatCurrency

        val amount = CryptoValue.fromMinor(CryptoCurrency.BTC, pendingTransaction.bigIntAmount)
        val fee = CryptoValue.fromMinor(CryptoCurrency.BTC, pendingTransaction.bigIntFee)
        val total = amount + fee

        return PaymentConfirmationDetails(
            fromLabel = pendingTransaction.sendingObject?.label ?: "",
            toLabel = destination,
            crypto = CryptoCurrency.BTC,
            fiatUnit = fiatCurrency,
            cryptoTotal = total.toStringWithoutSymbol(),
            cryptoAmount = amount.toStringWithoutSymbol(),
            cryptoFee = fee.toStringWithoutSymbol(),
            btcSuggestedFee = fee.toStringWithoutSymbol(),
            fiatFee = fee.toFiat(exchangeRates, fiatCurrency).toStringWithSymbol(),
            fiatAmount = amount.toFiat(exchangeRates, fiatCurrency).toStringWithSymbol(),
            fiatTotal = total.toFiat(exchangeRates, fiatCurrency).toStringWithSymbol(),
            isLargeTransaction = isLargeTransaction(pendingTransaction),
            hasConsumedAmounts = pendingTransaction.unspentOutputBundle!!.consumedAmount.compareTo(BigInteger.ZERO) == 1
        )
    }

    private fun isLargeTransaction(pendingTransaction: PendingTransaction): Boolean {
        val txSize = sendDataManager.estimateSize(
            pendingTransaction.unspentOutputBundle!!.spendableOutputs.size,
            2
        ) // assume change
        val relativeFee =
            pendingTransaction.bigIntFee.toDouble() / pendingTransaction.bigIntAmount.toDouble() * 100.0

        return (pendingTransaction.bigIntFee.toLong() > SendModel.LARGE_TX_FEE &&
                txSize > SendModel.LARGE_TX_SIZE &&
                relativeFee > SendModel.LARGE_TX_PERCENTAGE)
    }

    @SuppressLint("CheckResult")
    internal fun submitPayment() {
        view.showProgressDialog(R.string.please_wait)

        val legacyAddress = pendingTransaction!!.sendingObject!!.accountObject as LegacyAddress?
        val changeAddress = legacyAddress!!.address

        val keys = ArrayList<ECKey>()

        try {
            val walletKey = payloadDataManager.getAddressECKey(legacyAddress, secondPassword)
                ?: throw NullPointerException("ECKey was null")
            keys.add(walletKey)
        } catch (e: Exception) {
            view.dismissProgressDialog()
            view.showToast(R.string.transaction_failed, ToastCustom.TYPE_ERROR)
            return
        }

        compositeDisposable += sendDataManager.submitBtcPayment(
            pendingTransaction!!.unspentOutputBundle!!,
            keys,
            pendingTransaction!!.receivingAddress,
            changeAddress,
            pendingTransaction!!.bigIntFee,
            pendingTransaction!!.bigIntAmount
        ).doAfterTerminate { view.dismissProgressDialog() }
            .doOnError { Timber.e(it) }
            .subscribe(
                {
                    legacyAddress.archive()
                    updateArchivedUi(true, ::isArchivableBtc)

                    view.showTransactionSuccess()

                    // Update V2 balance immediately after spend - until refresh from server
                    val spentAmount =
                        pendingTransaction!!.bigIntAmount.toLong() + pendingTransaction!!.bigIntFee.toLong()

                    if (pendingTransaction!!.sendingObject!!.accountObject is Account) {
                        payloadDataManager.subtractAmountFromAddressBalance(
                            (pendingTransaction!!.sendingObject!!.accountObject as Account).xpub,
                            spentAmount
                        )
                    } else {
                        payloadDataManager.subtractAmountFromAddressBalance(
                            (pendingTransaction!!.senderAsLegacyAddress).address,
                            spentAmount
                        )
                    }

                    payloadDataManager.syncPayloadWithServer()
                        .emptySubscribe()

                    accountModel.transferFundsVisibility = View.GONE
                    view.setActivityResult(AppCompatActivity.RESULT_OK)
                },
                { view.showToast(R.string.send_failed, ToastCustom.TYPE_ERROR) }
            )
    }

    internal fun updateAccountLabel(newLabel: String) {
        val labelCopy = newLabel.trim { it <= ' ' }

        val walletSync: Completable

        if (!labelCopy.isEmpty()) {
            val revertLabel: String

            if (LabelUtil.isExistingLabel(payloadDataManager, bchDataManager, labelCopy)) {
                view.showToast(R.string.label_name_match, ToastCustom.TYPE_ERROR)
                return
            }

            when {
                account != null -> {
                    revertLabel = account!!.label ?: ""
                    account!!.label = labelCopy
                    walletSync = payloadDataManager.syncPayloadWithServer()
                }
                legacyAddress != null -> {
                    revertLabel = legacyAddress!!.label ?: ""
                    legacyAddress!!.label = labelCopy
                    walletSync = payloadDataManager.syncPayloadWithServer()
                }
                else -> {
                    revertLabel = bchAccount!!.label ?: ""
                    bchAccount!!.label = labelCopy
                    walletSync = metadataManager.saveToMetadata(
                        bchDataManager.serializeForSaving(),
                        BitcoinCashWallet.METADATA_TYPE_EXTERNAL
                    )
                }
            }

            compositeDisposable += walletSync
                .doOnError { Timber.e(it) }
                .doOnSubscribe { view.showProgressDialog(R.string.please_wait) }
                .doAfterTerminate { view.dismissProgressDialog() }
                .subscribe(
                    {
                        accountModel.label = labelCopy
                        view.setActivityResult(AppCompatActivity.RESULT_OK)
                        analytics.logEvent(WalletAnalytics.EditWalletName)
                    },
                    { revertLabelAndShowError(revertLabel) }
                )
        } else {
            view.showToast(R.string.label_cant_be_empty, ToastCustom.TYPE_ERROR)
        }
    }

    // Can't archive default account
    private fun isArchivableBtc(): Boolean = payloadDataManager.defaultAccount !== account

    // Can't archive default account
    private fun isArchivableBch(): Boolean =
        bchDataManager.getDefaultGenericMetadataAccount() !== bchAccount

    private fun revertLabelAndShowError(revertLabel: String) {
        // Remote save not successful - revert
        if (account != null) {
            account!!.label = revertLabel
        } else {
            legacyAddress!!.label = revertLabel
        }
        accountModel.label = revertLabel
        view.showToast(R.string.remote_save_ko, ToastCustom.TYPE_ERROR)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClickChangeLabel(view: View) {
        getView().promptAccountLabel(accountModel.label)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClickDefault(view: View) {
        val revertDefault: Int
        val walletSync: Completable

        if (account != null) {
            revertDefault = payloadDataManager.defaultAccountIndex
            walletSync = payloadDataManager.syncPayloadWithServer()
            payloadDataManager.wallet!!.hdWallets[0].defaultAccountIdx = accountIndex
        } else {
            revertDefault = bchDataManager.getDefaultAccountPosition()
            bchDataManager.setDefaultAccountPosition(accountIndex)
            walletSync = metadataManager.saveToMetadata(
                bchDataManager.serializeForSaving(),
                BitcoinCashWallet.METADATA_TYPE_EXTERNAL
            )
        }

        compositeDisposable += walletSync
            .doOnSubscribe { getView().showProgressDialog(R.string.please_wait) }
            .doOnError { Timber.e(it) }
            .doAfterTerminate { getView().dismissProgressDialog() }
            .subscribe(
                {
                    if (account != null) {
                        setDefault(isDefaultBtc(account))
                    } else {
                        setDefault(isDefaultBch(bchAccount))
                    }
                    analytics.logEvent(WalletAnalytics.ChangeDefault)
                    updateSwipeToReceiveAddresses()
                    getView().updateAppShortcuts()
                    getView().setActivityResult(AppCompatActivity.RESULT_OK)
                },
                { revertDefaultAndShowError(revertDefault) }
            )
    }

    private fun updateSwipeToReceiveAddresses() {
        compositeDisposable += swipeToReceiveHelper.generateAddresses()
            .subscribeOn(Schedulers.computation())
            .subscribe(
                { /* No-op */ },
                { Timber.e(it) }
            )
    }

    private fun revertDefaultAndShowError(revertDefault: Int) {
        // Remote save not successful - revert
        payloadDataManager.wallet!!.hdWallets[0].defaultAccountIdx = revertDefault
        view.showToast(R.string.remote_save_ko, ToastCustom.TYPE_ERROR)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClickShowXpub(view: View) {
        if (account != null || bchAccount != null) {
            getView().showXpubSharingWarning()
        } else {
            showAddressDetails()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClickArchive(view: View) {
        val title: String
        val subTitle: String

        if (account != null && account!!.isArchived ||
            bchAccount != null && bchAccount!!.isArchived ||
            legacyAddress != null && legacyAddress!!.isArchived
        ) {
            title = stringUtils.getString(R.string.unarchive)
            subTitle = stringUtils.getString(R.string.unarchive_are_you_sure)
        } else {
            title = stringUtils.getString(R.string.archive)
            subTitle = stringUtils.getString(R.string.archive_are_you_sure)
        }

        getView().promptArchive(title, subTitle)
    }

    private fun toggleArchived(): Boolean {
        return if (account != null) {
            account!!.isArchived = !account!!.isArchived
            account!!.isArchived
        } else if (legacyAddress != null) {
            if (legacyAddress!!.tag == LegacyAddress.ARCHIVED_ADDRESS) {
                legacyAddress!!.unarchive()
                false
            } else {
                legacyAddress!!.archive()
                true
            }
        } else {
            bchAccount!!.isArchived = !bchAccount!!.isArchived
            bchAccount!!.isArchived
        }
    }

    internal fun showAddressDetails() {
        var heading: String? = null
        var note: String? = null
        var copy: String? = null
        var qrString: String? = null
        var bitmap: Bitmap? = null

        when {
            account != null -> {
                heading = stringUtils.getString(R.string.extended_public_key)
                note = stringUtils.getString(R.string.scan_this_code)
                copy = stringUtils.getString(R.string.copy_xpub)
                qrString = account!!.xpub
            }
            legacyAddress != null -> {
                heading = stringUtils.getString(R.string.address)
                note = legacyAddress!!.address
                copy = stringUtils.getString(R.string.copy_address)
                qrString = legacyAddress!!.address
            }
            bchAccount != null -> {
                heading = stringUtils.getString(R.string.extended_public_key)
                note = stringUtils.getString(R.string.scan_this_code)
                copy = stringUtils.getString(R.string.copy_xpub)
                qrString = bchAccount!!.xpub
            }
        }

        val qrCodeDimension = 260
        val qrCodeEncoder = QRCodeEncoder(qrString!!, qrCodeDimension)
        try {
            bitmap = qrCodeEncoder.encodeAsBitmap()
        } catch (e: WriterException) {
            Timber.e(e)
        }

        view.showAddressDetails(heading, note, copy, bitmap, qrString)
        analytics.logEvent(WalletAnalytics.ShowXpub)
    }

    internal fun archiveAccount() {
        val isArchived = toggleArchived()
        val walletSync: Completable
        val updateTransactions: Completable
        val archivable: () -> Boolean

        if (account != null || legacyAddress != null) {
            walletSync = payloadDataManager.syncPayloadWithServer()
            archivable = ::isArchivableBtc
            updateTransactions = payloadDataManager.updateAllTransactions()
        } else {
            walletSync = metadataManager.saveToMetadata(
                bchDataManager.serializeForSaving(),
                BitcoinCashWallet.METADATA_TYPE_EXTERNAL
            )
            archivable = ::isArchivableBch
            updateTransactions =
                Completable.fromObservable(bchDataManager.getWalletTransactions(50, 50))
        }

        compositeDisposable += walletSync
            .doOnSubscribe { view.showProgressDialog(R.string.please_wait) }
            .doOnError { Timber.e(it) }
            .doAfterTerminate { view.dismissProgressDialog() }
            .subscribe(
                {
                    updateTransactions.emptySubscribe()
                    analytics.logEvent(AddressAnalytics.DeleteAddressLabel)
                    updateArchivedUi(isArchived, archivable)
                    view.setActivityResult(AppCompatActivity.RESULT_OK)
                    if (!isArchived)
                        analytics.logEvent(WalletAnalytics.UnArchiveWallet)
                    else
                        analytics.logEvent(WalletAnalytics.ArchiveWallet)
                },
                { view.showToast(R.string.remote_save_ko, ToastCustom.TYPE_ERROR) }
            )
    }

    /**
     * Generates a [PendingTransaction] object for a given legacy address, where the output is
     * the default account in the user's wallet
     *
     * @param legacyAddress The [LegacyAddress] you wish to transfer funds from
     * @param cryptoCurrency The currently selected currency
     * @return A [PendingTransaction]
     */
    private fun getPendingTransactionForLegacyAddress(
        cryptoCurrency: CryptoCurrency,
        legacyAddress: LegacyAddress?
    ): Observable<PendingTransaction> {
        val pendingTransaction = PendingTransaction()

        return Observables.zip(
            sendDataManager.getUnspentBtcOutputs(legacyAddress!!.address),
            coinSelectionRemoteConfig.enabled.toObservable()
        )
            .flatMap { (unspentOutputs, newCoinSelectionEnabled) ->
                val suggestedFeePerKb =
                    BigInteger.valueOf(dynamicFeeCache.btcFeeOptions!!.regularFee * 1000)

                val sweepableCoins = sendDataManager.getMaximumAvailable(
                    cryptoCurrency,
                    unspentOutputs,
                    suggestedFeePerKb,
                    newCoinSelectionEnabled
                )
                val sweepAmount = sweepableCoins.left

                var label: String = legacyAddress.label
                if (label.isEmpty()) {
                    label = legacyAddress.address
                }

                // To default account
                val defaultAccount = payloadDataManager.defaultAccount
                pendingTransaction.apply {
                    sendingObject = ItemAccount(
                        label = label,
                        balance = CryptoValue.fromMinor(cryptoCurrency, sweepAmount),
                        accountObject = legacyAddress,
                        address = legacyAddress.address
                    )
                    receivingObject = ItemAccount(
                        label = defaultAccount.label ?: "",
                        accountObject = defaultAccount
                    )
                    unspentOutputBundle = sendDataManager.getSpendableCoins(
                        unspentOutputs,
                        CryptoValue(cryptoCurrency, sweepAmount),
                        suggestedFeePerKb,
                        newCoinSelectionEnabled
                    )
                    bigIntAmount = sweepAmount
                    bigIntFee = unspentOutputBundle!!.absoluteFee
                }
                payloadDataManager.getNextReceiveAddress(defaultAccount)
            }
            .doOnNext { pendingTransaction.receivingAddress = it }
            .map { pendingTransaction }
    }
}
