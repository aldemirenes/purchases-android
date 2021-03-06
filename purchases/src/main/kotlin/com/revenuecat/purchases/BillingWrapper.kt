//  Purchases
//
//  Copyright © 2019 RevenueCat, Inc. All rights reserved.
//

package com.revenuecat.purchases

import android.app.Activity
import android.content.Context
import android.os.Handler
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams
import java.util.concurrent.ConcurrentLinkedQueue

internal class BillingWrapper internal constructor(
    private val clientFactory: ClientFactory,
    private val mainHandler: Handler
) : PurchasesUpdatedListener, BillingClientStateListener {

    internal var billingClient: BillingClient? = null
    internal var purchasesUpdatedListener: PurchasesUpdatedListener? = null
        set(value) {
            field = value
            if (value != null) {
                startConnection()
            } else {
                endConnection()
            }
        }

    private var clientConnected: Boolean = false
    private val serviceRequests = ConcurrentLinkedQueue<Runnable>()

    internal class ClientFactory(private val context: Context) {
        fun buildClient(listener: com.android.billingclient.api.PurchasesUpdatedListener): BillingClient {
            return BillingClient.newBuilder(context).setListener(listener).build()
        }
    }

    interface PurchasesUpdatedListener {
        fun onPurchasesUpdated(purchases: List<Purchase>)
        fun onPurchasesFailedToUpdate(
            purchases: List<Purchase>?,
            @BillingClient.BillingResponse responseCode: Int,
            message: String
        )
    }

    private fun executePendingRequests() {
        while (clientConnected && !serviceRequests.isEmpty()) {
            val request = serviceRequests.remove()
            request.run()
        }
    }

    private fun executeRequest(request: Runnable) {
        if (purchasesUpdatedListener != null) {
            serviceRequests.add(request)
            if (!clientConnected) {
                startConnection()
            } else {
                executePendingRequests()
            }
        } else {
            throw IllegalStateException("There is no listener set. Skipping." +
                "Make sure you set a listener before calling anything else.")
        }
    }

    private fun startConnection() {
        if (billingClient == null) {
            billingClient = clientFactory.buildClient(this)
        }
        debugLog("Starting connection for " + billingClient!!.toString())
        billingClient!!.startConnection(this)
    }

    private fun endConnection() {
        billingClient?.takeIf { it.isReady }?.let {
            debugLog("Ending connection for $it")
            it.endConnection()
        }
        billingClient = null
        clientConnected = false
    }

    private fun executeRequestOnUIThread(request: Runnable) {
        executeRequest(Runnable { mainHandler.post(request) })
    }

    fun querySkuDetailsAsync(
        @BillingClient.SkuType itemType: String,
        skuList: List<String>,
        onReceiveSkuDetails: (List<SkuDetails>) -> Unit,
        onError: (PurchasesError) ->  Unit
    ) {
        debugLog("Requesting products with identifiers: ${skuList.joinToString()}")
        executeRequest(Runnable {
            val params = SkuDetailsParams.newBuilder()
                .setType(itemType).setSkusList(skuList).build()
            billingClient!!.querySkuDetailsAsync(params) { responseCode, skuDetailsList ->
                if (responseCode == BillingClient.BillingResponse.OK) {
                    debugLog("Products request finished for ${skuList.joinToString()}")
                    debugLog("Retrieved skuDetailsList: ${skuDetailsList?.joinToString { it.toString() }}")
                    skuDetailsList?.takeUnless { it.isEmpty() }?.forEach {
                        debugLog("${it.sku} - $it")
                    }

                    onReceiveSkuDetails(skuDetailsList ?: emptyList())
                } else {
                    errorLog("Error ${responseCode.getBillingResponseCodeName()} when fetching products.")
                    onError(PurchasesError(Purchases.ErrorDomains.PLAY_BILLING, responseCode, "Error fetching products"))
                }
            }
        })
    }

    fun makePurchaseAsync(
        activity: Activity,
        appUserID: String,
        sku: String,
        oldSkus: ArrayList<String>,
        @BillingClient.SkuType skuType: String
    ) {

        executeRequestOnUIThread(Runnable {
            val builder = BillingFlowParams.newBuilder()
                .setSku(sku)
                .setType(skuType)
                .setAccountId(appUserID)

            if (oldSkus.size > 0) {
                builder.setOldSkus(oldSkus)
            }

            val params = builder.build()

            @BillingClient.BillingResponse val response =
                billingClient!!.launchBillingFlow(activity, params)
            if (response != BillingClient.BillingResponse.OK) {
                errorLog("Failed to launch billing intent $response")
            }
        })
    }

    fun queryPurchaseHistoryAsync(
        @BillingClient.SkuType skuType: String,
        onReceivePurchaseHistory: (List<Purchase>) -> Unit,
        onReceivePurchaseHistoryError: (PurchasesError) -> Unit
    ) {
        debugLog("Querying purchase history for type $skuType")
        executeRequest(Runnable {
            billingClient!!.queryPurchaseHistoryAsync(skuType) { responseCode, purchasesList ->
                if (responseCode == BillingClient.BillingResponse.OK) {
                    purchasesList.takeUnless { it.isEmpty() }?.forEach {
                        debugLog("Purchase history retrieved ${it.toHumanReadableDescription()}")
                    } ?: debugLog("Purchase history is empty.")
                    onReceivePurchaseHistory(purchasesList)
                } else {
                    errorLog("Error receiving purchase history ${responseCode.getBillingResponseCodeName()}")
                    onReceivePurchaseHistoryError(
                        PurchasesError(
                            Purchases.ErrorDomains.PLAY_BILLING,
                            responseCode,
                            "Error receiving purchase history"
                        )
                    )
                }
            }
        })
    }

    fun consumePurchase(token: String) {
        debugLog("Consuming purchase with token $token")
        executeRequest(Runnable { billingClient!!.consumeAsync(token) { _, _ -> } })
    }

    override fun onPurchasesUpdated(@BillingClient.BillingResponse responseCode: Int, purchases: List<Purchase>?) {
        if (responseCode == BillingClient.BillingResponse.OK && purchases != null) {
            purchases.forEach {
                debugLog("BillingWrapper purchases updated: ${it.toHumanReadableDescription()}")
            }
            purchasesUpdatedListener!!.onPurchasesUpdated(purchases)
        } else {
            debugLog("BillingWrapper purchases failed to update: responseCode ${responseCode.getBillingResponseCodeName()}" +
                    "${purchases?.takeUnless { it.isEmpty() }?.let { purchase ->
                        "Purchases:" + purchase.joinToString(
                            ", ",
                            transform = { it.toHumanReadableDescription() }
                        )
                    }}"
            )

            purchasesUpdatedListener!!.onPurchasesFailedToUpdate(
                purchases,
                if (purchases == null && responseCode == BillingClient.BillingResponse.OK)
                    BillingClient.BillingResponse.ERROR
                else
                    responseCode,
                "Error updating purchases ${responseCode.getBillingResponseCodeName()}"
            )
        }
    }

    override fun onBillingSetupFinished(@BillingClient.BillingResponse responseCode: Int) {
        if (responseCode == BillingClient.BillingResponse.OK) {
            debugLog("Billing Service Setup finished for ${billingClient?.toString()}")
            clientConnected = true
            executePendingRequests()
        } else {
            errorLog("Billing Service Setup finished with error code: ${responseCode.getBillingResponseCodeName()}")
        }
    }

    override fun onBillingServiceDisconnected() {
        clientConnected = false
        debugLog("Billing Service disconnected for ${billingClient?.toString()}")
    }
}
