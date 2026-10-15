package com.safetap

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

// Google Play Billing for the SafeTap Family subscription.
//
// The two product IDs below must exist in Play Console under
// Monetize > Subscriptions before this can sell anything:
//   safetap_family_monthly
//   safetap_family_yearly
//
// Notes:
// - Real purchases only work in a release build uploaded to Play
//   Console (internal testing track is enough) with license testers.
//   Debug builds get BillingResponseCode.BILLING_UNAVAILABLE.
// - The free SOS alert is never gated: isPremium only unlocks the
//   Family conveniences (more contacts, unlimited check-ins, history).
class BillingManager(private val activity: Activity) {

    companion object {
        const val PRODUCT_MONTHLY = "safetap_family_monthly"
        const val PRODUCT_YEARLY = "safetap_family_yearly"
    }

    private var billingClient: BillingClient? = null

    /** Called with the subscription products Play knows about (may be empty). */
    var onProducts: ((List<ProductDetails>) -> Unit)? = null

    /** Called after a purchase flow finishes: success + a human message. */
    var onPurchase: ((Boolean, String) -> Unit)? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { acknowledge(it) }
                onPurchase?.invoke(true, "Welcome to SafeTap Family.")
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                onPurchase?.invoke(false, "Purchase cancelled.")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                onPurchase?.invoke(true, "You already own SafeTap Family.")
            else ->
                onPurchase?.invoke(false, "Purchase failed (${result.debugMessage}).")
        }
    }

    fun connect(onReady: (Boolean) -> Unit) {
        if (billingClient?.isReady == true) {
            onReady(true)
            return
        }
        billingClient = BillingClient.newBuilder(activity)
            .setListener(purchasesListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                onReady(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                // Play will call us back; we reconnect lazily on next use.
            }
        })
    }

    fun queryProducts() {
        val client = billingClient ?: return
        val productList = listOf(PRODUCT_MONTHLY, PRODUCT_YEARLY).map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        client.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                onProducts?.invoke(detailsResult.productDetailsList)
            } else {
                onProducts?.invoke(emptyList())
            }
        }
    }

    fun launchPurchase(product: ProductDetails) {
        val client = billingClient ?: return
        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    /** True if Play reports an active, purchased Family subscription. */
    fun restore(onDone: (Boolean) -> Unit) {
        val client = billingClient ?: return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { result, purchases ->
            val active = result.responseCode == BillingClient.BillingResponseCode.OK &&
                purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            onDone(active)
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED ||
            purchase.isAcknowledged
        ) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient?.acknowledgePurchase(params) { _ -> }
    }

    fun disconnect() {
        billingClient?.endConnection()
        billingClient = null
    }
}
