# Android checkout + Biteship delivery integration

A real-time checkout and delivery flow for Android apps: Jetpack Compose screens for checkout, payment, and order tracking, paired with a Supabase Edge Function that automatically creates a delivery order with [Biteship](https://biteship.com/) once payment is confirmed via Midtrans.

End-to-end: from "checkout" to "package delivered," fully automatic, no manual order creation in between.

## What's included

```
android-app/
├── CheckoutScreen.kt           Address, schedule, and instant courier selection
├── PaymentMethodScreen.kt      Payment methods, vouchers, points, Midtrans Snap WebView
└── TransactionDetailScreen.kt  Order status, courier tracking, WhatsApp support link

supabase-function/
└── index.ts                    biteship-create-order Edge Function
```

## How it works

1. **Customer checkout** — the customer picks a delivery address, a time slot, and an instant courier (e.g. Gojek/Grab-style), then confirms payment.
2. **Payment succeeds** — Midtrans sends an asynchronous transaction status notification to a webhook.
3. **Edge function: create order** — the webhook validates the payment, then invokes `biteship-create-order`, which forwards the order to Biteship.
4. **Biteship dispatches a courier** — Biteship routes the order to its courier network and matches a nearby driver.
5. **Save `biteship_order_id`** — the returned order ID is stored on the transaction so its delivery status can be looked up later.
6. **App calls track-order** — when the customer opens the order detail screen, the app calls a tracking function using the stored order ID.
7. **Poll for the latest status** — the app periodically requests the latest delivery status from Biteship.
8. **Show courier info** — the driver's name, photo, plate number, and a tracking link are shown to the customer.
9. **Order complete** — once delivered, the status updates and the completed order is kept in order history.

This repo contains the pieces for steps 1, 2, 3, 5, 6, and 9. Steps 2 (Midtrans webhook) and 7/8 (status polling, tracking UI) live in their own functions/screens and aren't included here — open an issue or PR if you'd like a reference implementation for those too.

## Requirements

- A Supabase project with Edge Functions enabled
- A [Biteship](https://biteship.com/) account with an active API key
- A Midtrans account for handling payments (or another payment provider — the create-order step only needs to be triggered after payment is confirmed)
- An Android app using Jetpack Compose, Supabase Kotlin client, and OkHttp

## Database setup

The Edge Function expects two tables:

**`transactions`**

| Column | Type | Description |
|---|---|---|
| `transaction_id` | `text` | Unique order/transaction identifier |
| `address_id` | `uuid` / `text` | Foreign key to the destination address |
| `cart_items` | `jsonb` | Array of `{ product_name, product_price, quantity }` |
| `courier_code` | `text` | e.g. `"gojek"`, `"grab"` — null/empty means store pickup |
| `courier_type` | `text` | e.g. `"instant"` |
| `biteship_order_id` | `text` | Filled in automatically once the order is created |
| `waybill_id`, `tracking_id` | `text` | Filled in automatically from Biteship's response |

**`addresses`**

| Column | Type | Description |
|---|---|---|
| `full_address` | `text` | Destination address |
| `recipient_name` | `text` | |
| `phone_number` | `text` | |
| `latitude`, `longitude` | `numeric` | Required for instant courier routing |
| `courier_note` | `text` | Optional delivery note |

Adjust table/column names in the `CONFIG` section of `supabase-function/index.ts` if your schema differs.

## Setup

### 1. Deploy the Edge Function

Copy `supabase-function/index.ts` into your project at:

```
supabase/functions/biteship-create-order/index.ts
```

Deploy it:

```bash
supabase functions deploy biteship-create-order
```

### 2. Set environment variables

In your Supabase project (Project Settings → Edge Functions → Secrets), set:

| Variable | Description |
|---|---|
| `BITESHIP_API_KEY` | Your Biteship API key |
| `SUPABASE_SERVICE_ROLE_KEY` | Your Supabase service role key |

`SUPABASE_URL` is provided automatically inside Edge Functions.

> **Security note:** the service role key bypasses Row Level Security. It must only be used server-side, inside the Edge Function — never expose it to the client or commit it to your repo.

### 3. Configure your store details

Open the `CONFIG` section at the top of `supabase-function/index.ts` and set your store's coordinates, name, phone, email, and origin address — these are used as the shipper/origin on every order:

```ts
const STORE_LATITUDE  = -6.200000;
const STORE_LONGITUDE = 106.816666;
const SHIPPER_NAME    = "My Store";
const SHIPPER_PHONE   = "+62800000000";
const SHIPPER_EMAIL   = "store@example.com";
const ORIGIN_ADDRESS  = "Your store's full address goes here";
```

### 4. Trigger the function after payment

`biteship-create-order` expects to be called with `{ "order_id": "<transaction_id>" }` once a transaction's payment is confirmed — typically from your payment webhook (e.g. a Midtrans notification handler), not directly from the client.

```ts
await supabase.functions.invoke('biteship-create-order', {
  body: { order_id: transactionId },
});
```

### 5. Add the Android screens

Copy the files in `android-app/` into your project under `com.example.app.ui` (or update the package declaration to match your own app's package name). These screens assume:

- A `CartViewModel` exposing the current cart as a `StateFlow`
- A Supabase Kotlin client configured at `com.example.app.network.SupabaseClient`
- A `biteship-rates` Edge Function (not included) used by `CheckoutScreen` to fetch live shipping quotes before checkout

## API reference

**POST** `/functions/v1/biteship-create-order`

Body:
```json
{ "order_id": "TRX-12345" }
```

Response (success):
```json
{
  "success": true,
  "biteship_order_id": "abc123",
  "waybill_id": "WB-001",
  "tracking_id": "TRK-001"
}
```

Response (skipped — pickup order or already created):
```json
{ "skipped": true, "reason": "Pickup order, no courier" }
```

## License

MIT — use freely in personal and commercial projects.
