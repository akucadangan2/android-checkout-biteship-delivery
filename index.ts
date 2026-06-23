// supabase/functions/biteship-create-order/index.ts
//
// Creates a delivery order with Biteship after a transaction is confirmed paid.
// Typically triggered by a payment webhook (e.g. Midtrans) once payment succeeds.
//
// Before deploying, set these environment variables in your Supabase project:
//   BITESHIP_API_KEY
//   SUPABASE_URL              (automatically available in Edge Functions)
//   SUPABASE_SERVICE_ROLE_KEY (Supabase service role key, NEVER use this on the client)
//
// Update the store/shipper details and table/column names in the "CONFIG"
// section below to match your business and database schema.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ===================== CONFIG =====================
// Store (shipper/origin) details — must match the address registered with
// your courier aggregator account. Double-check these before going live.
const STORE_LATITUDE  = -6.200000;
const STORE_LONGITUDE = 106.816666;
const SHIPPER_NAME    = "My Store";
const SHIPPER_PHONE   = "+62800000000";
const SHIPPER_EMAIL   = "store@example.com";
const ORIGIN_ADDRESS  = "Your store's full address goes here";

// Database table/column names — update if your schema differs.
const TRANSACTIONS_TABLE = "transactions";
const ADDRESSES_TABLE    = "addresses";
// ====================================================

const BITESHIP_API_KEY    = Deno.env.get("BITESHIP_API_KEY")!;
const BITESHIP_ORDERS_URL = "https://api.biteship.com/v1/orders";
const SUPABASE_URL         = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const { order_id } = await req.json();
    if (!order_id) {
      return new Response(JSON.stringify({ error: "Missing order_id" }), {
        status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

    // 1. Fetch the transaction
    const { data: trx, error: trxErr } = await supabase
      .from(TRANSACTIONS_TABLE)
      .select("transaction_id, address_id, cart_items, courier_code, courier_type, courier_name, shipping_cost, biteship_order_id")
      .eq("transaction_id", order_id)
      .maybeSingle();

    if (trxErr || !trx) {
      console.error("[BiteshipOrder] Transaction not found:", order_id, trxErr);
      return new Response(JSON.stringify({ error: "Transaction not found" }), {
        status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 2. Skip if this isn't a delivery order (store pickup), or if the order was already created
    if (!trx.courier_code) {
      console.log("[BiteshipOrder] Skipped — not a delivery order (store pickup):", order_id);
      return new Response(JSON.stringify({ skipped: true, reason: "Pickup order, no courier" }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }
    if (trx.biteship_order_id) {
      console.log("[BiteshipOrder] Skipped — Biteship order already exists:", order_id, trx.biteship_order_id);
      return new Response(JSON.stringify({ skipped: true, reason: "Order already created", biteship_order_id: trx.biteship_order_id }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 3. Fetch the destination address
    const { data: address, error: addrErr } = await supabase
      .from(ADDRESSES_TABLE)
      .select("full_address, recipient_name, phone_number, latitude, longitude, courier_note")
      .eq("id", trx.address_id)
      .maybeSingle();

    if (addrErr || !address) {
      console.error("[BiteshipOrder] Address not found:", trx.address_id, addrErr);
      return new Response(JSON.stringify({ error: "Address not found" }), {
        status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 4. Build the item list from the saved cart_items
    const cartItems = (trx.cart_items ?? []) as Array<Record<string, unknown>>;
    const items = cartItems.map((item) => ({
      name:     String(item.product_name ?? "Product"),
      value:    Number(item.product_price) || 0,
      quantity: Number(item.quantity) || 1,
      weight:   500, // default 500g per item — same assumption used when checking shipping rates
    }));

    // 5. Build the Create Order payload — coordinates are required since
    // on-demand couriers (e.g. Gojek/Grab-style instant courier) route by lat/lng.
    const orderPayload = {
      shipper_contact_name:  SHIPPER_NAME,
      shipper_contact_phone: SHIPPER_PHONE,
      shipper_contact_email: SHIPPER_EMAIL,
      shipper_organization:  SHIPPER_NAME,

      origin_contact_name:  SHIPPER_NAME,
      origin_contact_phone: SHIPPER_PHONE,
      origin_address:       ORIGIN_ADDRESS,
      origin_coordinate: {
        latitude:  STORE_LATITUDE,
        longitude: STORE_LONGITUDE,
      },

      destination_contact_name:  address.recipient_name,
      destination_contact_phone: address.phone_number,
      destination_address:       address.full_address,
      destination_note:          address.courier_note ?? undefined,
      destination_coordinate: {
        latitude:  Number(address.latitude),
        longitude: Number(address.longitude),
      },

      courier_company: trx.courier_code,
      courier_type:    trx.courier_type || "instant",
      delivery_type:   "now",
      order_note:       `Order ${order_id} - ${SHIPPER_NAME}`,
      reference_id:     order_id, // prevents duplicate orders if this function is triggered again
      items,
    };

    console.log("[BiteshipOrder] Creating order:", order_id, "->", trx.courier_code);

    const res = await fetch(BITESHIP_ORDERS_URL, {
      method: "POST",
      headers: {
        "Content-Type":  "application/json",
        "Authorization": `Bearer ${BITESHIP_API_KEY}`,
      },
      body: JSON.stringify(orderPayload),
    });

    const data = await res.json();

    if (!res.ok || data.success === false) {
      console.error("[BiteshipOrder] Failed to create order:", JSON.stringify(data));
      return new Response(JSON.stringify({ error: "Failed to create Biteship order", detail: data }), {
        status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    console.log("[BiteshipOrder] Order created:", data.id, "| waybill:", data.courier?.waybill_id);

    // 6. Save the order result back to the transaction
    await supabase
      .from(TRANSACTIONS_TABLE)
      .update({
        biteship_order_id: data.id,
        waybill_id:        data.courier?.waybill_id ?? null,
        tracking_id:       data.courier?.tracking_id ?? null,
      })
      .eq("transaction_id", order_id);

    return new Response(JSON.stringify({
      success: true,
      biteship_order_id: data.id,
      waybill_id: data.courier?.waybill_id ?? null,
      tracking_id: data.courier?.tracking_id ?? null,
    }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });

  } catch (error) {
    console.error("[BiteshipOrder] Unexpected error:", error);
    return new Response(JSON.stringify({ error: String(error) }), {
      status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
