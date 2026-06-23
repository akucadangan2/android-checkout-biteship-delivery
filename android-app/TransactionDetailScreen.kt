package com.example.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.app.network.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.asImageBitmap
import java.text.NumberFormat
import java.util.Locale

private const val STORE_WA_DETAIL = "6280000000000" // TODO: replace with your store's WhatsApp number

// Generate QR Code bitmap menggunakan ZXing (pure Java, no extra library)
fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
    return try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val hints = mapOf(
            com.google.zxing.EncodeHintType.MARGIN to 1,
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H
        )
        val matrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    }
}

@kotlinx.serialization.Serializable
data class AddressDetail(
    val id: String = "",
    val label: String = "",
    @kotlinx.serialization.SerialName("full_address") val fullAddress: String = "",
    @kotlinx.serialization.SerialName("recipient_name") val recipientName: String = "",
    @kotlinx.serialization.SerialName("phone_number") val phoneNumber: String = "",
    @kotlinx.serialization.SerialName("courier_note") val courierNote: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    navController: NavController,
    transactionId: String
) {
    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val fmt        = NumberFormat.getCurrencyInstance(Locale("in", "ID"))
    val red        = Color(0xFFC62828)
    val redLight   = Color(0xFFFFEBEE)
    val blue       = Color(0xFF1565C0)
    val blueLight  = Color(0xFFE3F2FD)
    val green      = Color(0xFF2E7D32)
    val greenLight = Color(0xFFE8F5E9)
    val orange     = Color(0xFFE65100)
    val gray1      = Color(0xFFF5F5F7)
    val gray2      = Color(0xFFEEEEEE)
    val textPri    = Color(0xFF1A1A1A)
    val textSec    = Color(0xFF888888)

    var trx            by remember { mutableStateOf<TransactionRecord?>(null) }
    var address        by remember { mutableStateOf<AddressDetail?>(null) }
    var isLoading      by remember { mutableStateOf(true) }
    var isCreatingLink by remember { mutableStateOf(false) }
    var isLoadingCourier   by remember { mutableStateOf(false) }
    var courierInfo        by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var showCourierDialog  by remember { mutableStateOf(false) }
    var courierError       by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(transactionId) {
        isLoading = true
        try {
            trx = SupabaseClient.client
                .from("transactions")
                .select { filter { eq("transaction_id", transactionId) } }
                .decodeSingle<TransactionRecord>()
            if (!trx?.addressId.isNullOrEmpty()) {
                try {
                    address = SupabaseClient.client
                        .from("addresses")
                        .select { filter { eq("id", trx!!.addressId!!) } }
                        .decodeSingle<AddressDetail>()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { e.printStackTrace() }
        isLoading = false
    }

    // ── Realtime listener — update otomatis saat status berubah ──────────────
    DisposableEffect(transactionId) {
        val channel = SupabaseClient.client.realtime.channel("detail-$transactionId")
        val job = channel
            .postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "transactions"
            }
            .onEach { action ->
                if (action is PostgresAction.Update) {
                    // Fetch ulang data terbaru setelah ada perubahan
                    try {
                        val updated = SupabaseClient.client
                            .from("transactions")
                            .select { filter { eq("transaction_id", transactionId) } }
                            .decodeSingle<TransactionRecord>()
                        trx = updated
                    } catch (_: Exception) {}
                }
            }
            .launchIn(scope)

        scope.launch { channel.subscribe() }

        onDispose {
            job.cancel()
            scope.launch {
                try { channel.unsubscribe() } catch (_: Exception) {}
            }
        }
    }

    // Status config
    val statusConfig = when (trx?.status) {
        "Diproses"            -> Triple(blue,   blueLight,  Icons.Rounded.LocalShipping)
        "Selesai"             -> Triple(green,  greenLight, Icons.Rounded.CheckCircle)
        "Dibatalkan"          -> Triple(red,    redLight,   Icons.Rounded.Cancel)
        else                  -> Triple(orange, Color(0xFFFFF3E0), Icons.Rounded.HourglassTop)
    }

    Scaffold(
        containerColor = gray1,
        topBar = {
            TopAppBar(
                title = { Text("Detail Pesanan", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        } else {
                            navController.navigate("transaction") {
                                popUpTo("home") { inclusive = false }
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    // E-Receipt shortcut
                    if (trx != null) {
                        OutlinedButton(
                            onClick = { navController.navigate("e_receipt/" + java.net.URLEncoder.encode(transactionId, "UTF-8")) },
                            modifier = Modifier.padding(end = 12.dp).height(34.dp),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Rounded.Receipt, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("E-Receipt", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = red)
            )
        }
    ) { pad ->
        if (isLoading || trx == null) {
            Box(Modifier.fillMaxSize().padding(pad).background(gray1),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = red, strokeWidth = 3.dp)
            }
            return@Scaffold
        }

        val t        = trx!!
        val subTotal = t.cartItems.sumOf { it.productPrice * it.quantity }
        val ongkir   = (t.grandTotal - subTotal).coerceAtLeast(0)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Status Hero (merah transparan) ────────────────────────────────
            item {
                Box(modifier = Modifier.fillMaxWidth().background(red).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp)
                                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(statusConfig.third, null, tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Status pesanan", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                                Text(t.status, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(formatTanggal(t.createdAt), fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                            Surface(color = Color.White, shape = RoundedCornerShape(10.dp)) {
                                Text(
                                    if (t.status == "Selesai" || t.status == "Dibatalkan") "Selesai" else "Aktif",
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = red,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Konten putih rounded ──────────────────────────────────────────
            // ── QR Code Pickup (khusus pesanan toko/pickup) ──────────────────
            if (t.addressId.isNullOrEmpty()) {
                item {
                    val qrBitmap = remember(t.transactionId) {
                        generateQrBitmap(t.transactionId, 512)
                    }
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shadowElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("Tiket Pickup", fontSize = 12.sp, color = textSec)
                                    Text("Tunjukkan ke Kasir", fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold, color = textPri)
                                }
                                Surface(
                                    color = when (t.status) {
                                        "Selesai" -> greenLight; "Dibatalkan" -> redLight
                                        else -> blueLight
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(t.status, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        color = when (t.status) {
                                            "Selesai" -> green; "Dibatalkan" -> red
                                            else -> blue
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = gray2)
                            Spacer(Modifier.height(16.dp))
                            Box(modifier = Modifier.size(220.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .border(1.dp, gray2, RoundedCornerShape(12.dp))
                                .padding(12.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "QR Code Pickup",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(t.transactionId, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace, color = textPri)
                            Text("Scan QR ini di kasir untuk mengambil pesanan",
                                fontSize = 11.sp, color = textSec,
                                modifier = Modifier.padding(top = 4.dp))
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = gray2)
                            Spacer(Modifier.height(12.dp))
                            t.cartItems.take(3).forEach { item ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(item.productName, fontSize = 12.sp, color = textPri,
                                        modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text("x${item.quantity}", fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold, color = blue,
                                        modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                            if (t.cartItems.size > 3)
                                Text("+ ${t.cartItems.size - 3} barang lainnya",
                                    fontSize = 11.sp, color = textSec,
                                    modifier = Modifier.padding(top = 2.dp))
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = gray2)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total Pembayaran", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(fmt.format(t.grandTotal), fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp, color = red)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(gray1)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Info Pesanan
                    ModernCard {
                        CardHeader(icon = Icons.Rounded.Receipt, title = "Info Pesanan")
                        Spacer(Modifier.height(8.dp))
                        ModernInfoRow("No. Pesanan", t.transactionId, mono = true)
                        HorizontalDivider(color = gray2, modifier = Modifier.padding(vertical = 4.dp))
                        ModernInfoRow("Tanggal", formatTanggal(t.createdAt))
                        if (!t.courierName.isNullOrEmpty()) {
                            HorizontalDivider(color = gray2, modifier = Modifier.padding(vertical = 4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("Kurir", fontSize = 12.sp, color = textSec)
                                Surface(color = blueLight, shape = RoundedCornerShape(8.dp)) {
                                    Text(t.courierName, fontSize = 11.sp, color = blue,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    // Alamat
                    address?.let { addr ->
                        ModernCard {
                            CardHeader(icon = Icons.Rounded.LocationOn, title = "Alamat Pengiriman")
                            Spacer(Modifier.height(10.dp))
                            Text(addr.recipientName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPri)
                            Text(addr.phoneNumber, fontSize = 12.sp, color = textSec, modifier = Modifier.padding(top = 2.dp))
                            Text(addr.fullAddress, fontSize = 12.sp, color = textSec,
                                lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                            if (!addr.courierNote.isNullOrEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Surface(color = Color(0xFFFFF8E1), shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Rounded.Info, null, tint = orange,
                                            modifier = Modifier.size(14.dp).padding(top = 1.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("${addr.courierNote}", fontSize = 11.sp,
                                            color = Color(0xFF795548), lineHeight = 16.sp)
                                    }
                                }
                            }

                            // Tombol Hubungi Kurir — hanya muncul kalau order ini sudah
                            // punya waybill (artinya order Biteship berhasil dibuat)
                            if (!t.waybillId.isNullOrEmpty() && t.status != "Dibatalkan") {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        showCourierDialog = true
                                        if (courierInfo == null) {
                                            isLoadingCourier = true
                                            courierError = null
                                            scope.launch {
                                                try {
                                                    val body = kotlinx.serialization.json.buildJsonObject {
                                                        put("order_id", kotlinx.serialization.json.JsonPrimitive(t.transactionId))
                                                    }
                                                    val resp = SupabaseClient.client.functions
                                                        .invoke("biteship-track-order", body = body)
                                                    val json = kotlinx.serialization.json.Json
                                                        .parseToJsonElement(resp.bodyAsText()).jsonObject
                                                    if (json["success"]?.jsonPrimitive?.content == "true") {
                                                        courierInfo = json
                                                    } else {
                                                        courierError = "Gagal mengambil info kurir, coba lagi nanti"
                                                    }
                                                } catch (e: Exception) {
                                                    courierError = "Gagal mengambil info kurir, coba lagi nanti"
                                                } finally {
                                                    isLoadingCourier = false
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = blueLight)
                                ) {
                                    Icon(Icons.Rounded.Phone, null, tint = blue, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Hubungi Kurir", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = blue)
                                }
                            }
                        }
                    }

                    // Produk
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        shadowElevation = 1.dp
                    ) {
                        Column {
                            Row(modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.ShoppingBag, null, tint = red, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("${t.cartItems.size} Produk", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPri)
                            }
                            HorizontalDivider(color = gray2)
                            t.cartItems.forEach { item ->
                                Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = item.productImageUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                            .background(gray1).border(0.5.dp, gray2, RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.productName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                            color = textPri, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(fmt.format(item.productPrice), fontSize = 11.sp,
                                            color = textSec, modifier = Modifier.padding(top = 2.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.End) {
                                        Surface(color = gray1, shape = RoundedCornerShape(6.dp)) {
                                            Text("x${item.quantity}", fontSize = 11.sp, color = textSec,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                        }
                                        Text(fmt.format(item.productPrice * item.quantity),
                                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                            color = blue, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                                HorizontalDivider(color = gray2, modifier = Modifier.padding(horizontal = 12.dp))
                            }

                            // Rincian harga
                            Column(modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Subtotal produk", fontSize = 12.sp, color = textSec)
                                    Text(fmt.format(subTotal), fontSize = 12.sp, color = textPri)
                                }
                                if (ongkir > 0) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Ongkos kirim", fontSize = 12.sp, color = textSec)
                                            if (!t.courierName.isNullOrEmpty()) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(color = blueLight, shape = RoundedCornerShape(6.dp)) {
                                                    Text(t.courierName, fontSize = 10.sp, color = blue,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                                }
                                            }
                                        }
                                        Text(fmt.format(ongkir), fontSize = 12.sp, color = textPri)
                                    }
                                }
                                HorizontalDivider(color = gray2)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("Total belanja", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textPri)
                                    Text(fmt.format(t.grandTotal), fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold, color = red)
                                }
                            }
                        }
                    }

                    // Tombol Aksi
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // E-Receipt
                        Button(
                            onClick = { navController.navigate("e_receipt/" + java.net.URLEncoder.encode(t.transactionId, "UTF-8")) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = blue)
                        ) {
                            Icon(Icons.Rounded.Receipt, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Lihat E-Receipt", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }

                        // WhatsApp
                        OutlinedButton(
                            onClick = {
                                val msg = "Halo Admin My Store 👋\n\n" +
                                        "Saya ingin menanyakan pesanan:\n" +
                                        "📦 No: ${t.transactionId}\n" +
                                        "📌 Status: ${t.status}\n" +
                                        "💰 Total: ${fmt.format(t.grandTotal)}\n\n" +
                                        "Mohon bantuannya ya 🙏"
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://wa.me/$STORE_WA_DETAIL?text=${Uri.encode(msg)}")))
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, green),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = green)
                        ) {
                            Icon(Icons.Rounded.Chat, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Hubungi Toko via WhatsApp", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }

                        // Lanjut Bayar
                        if (t.status == "Menunggu Pembayaran") {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isCreatingLink = true
                                        try {
                                            // Cek snap_token yang sudah tersimpan di DB
                                            val existing = t.snapToken
                                            if (!existing.isNullOrEmpty()) {
                                                // Langsung buka WebView dengan token yang ada
                                                val eTrx = android.net.Uri.encode(t.transactionId)
                                                navController.navigate("midtrans_snap/${android.net.Uri.encode(existing)}/$eTrx/0")
                                            } else {
                                                // Belum ada token — request baru ke Edge Function
                                                val profile = try {
                                                    val user = SupabaseClient.client.auth.currentUserOrNull()
                                                    SupabaseClient.client.from("profiles")
                                                        .select { filter { eq("id", user?.id ?: "") } }
                                                        .decodeSingleOrNull<ProfileRecord>()
                                                } catch (_: Exception) { null }
                                                val user = SupabaseClient.client.auth.currentUserOrNull()

                                                val snapBody = kotlinx.serialization.json.buildJsonObject {
                                                    put("order_id",       kotlinx.serialization.json.JsonPrimitive(t.transactionId))
                                                    put("gross_amount",   kotlinx.serialization.json.JsonPrimitive(t.grandTotal))
                                                    put("items", kotlinx.serialization.json.JsonArray(t.cartItems.map {
                                                        kotlinx.serialization.json.buildJsonObject {
                                                            put("id",       kotlinx.serialization.json.JsonPrimitive(it.productName))
                                                            put("name",     kotlinx.serialization.json.JsonPrimitive(it.productName))
                                                            put("price",    kotlinx.serialization.json.JsonPrimitive(it.productPrice))
                                                            put("quantity", kotlinx.serialization.json.JsonPrimitive(it.quantity))
                                                        }
                                                    }))
                                                    put("customer_name",  kotlinx.serialization.json.JsonPrimitive(profile?.fullName  ?: user?.email ?: "Pelanggan"))
                                                    put("customer_email", kotlinx.serialization.json.JsonPrimitive(user?.email        ?: ""))
                                                    put("customer_phone", kotlinx.serialization.json.JsonPrimitive(profile?.phoneNumber ?: ""))
                                                }

                                                val resp = SupabaseClient.client.functions
                                                    .invoke("create-payment", body = snapBody)
                                                val snapToken = try {
                                                    kotlinx.serialization.json.Json
                                                        .parseToJsonElement(resp.bodyAsText())
                                                        .jsonObject["token"]?.jsonPrimitive?.content ?: ""
                                                } catch (_: Exception) { "" }

                                                if (snapToken.isNotEmpty()) {
                                                    val eTrx = android.net.Uri.encode(t.transactionId)
                                                    navController.navigate("midtrans_snap/${android.net.Uri.encode(snapToken)}/$eTrx/0")
                                                }
                                            }
                                        } catch (e: Exception) { e.printStackTrace() }
                                        isCreatingLink = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = red),
                                enabled = !isCreatingLink
                            ) {
                                if (isCreatingLink) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Memproses...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                } else {
                                    Icon(Icons.Rounded.Payment, null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Lanjut Bayar", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }

                        // Belanja Lagi
                        if (t.status == "Selesai" || t.status == "Dibatalkan") {
                            OutlinedButton(
                                onClick = { navController.navigate("home") { popUpTo("home") { inclusive = true } } },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, blue),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = blue)
                            ) {
                                Icon(Icons.Rounded.ShoppingCart, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Belanja Lagi", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Dialog Hubungi Kurir ────────────────────────────────────────────────
    if (showCourierDialog) {
        AlertDialog(
            onDismissRequest = { showCourierDialog = false },
            title = { Text("Info Kurir", fontWeight = FontWeight.Bold) },
            text = {
                when {
                    isLoadingCourier -> {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = red)
                        }
                    }
                    courierError != null -> {
                        Text(courierError ?: "Terjadi kesalahan", color = textSec, fontSize = 13.sp)
                    }
                    else -> {
                        val driverName  = courierInfo?.get("driver_name")?.jsonPrimitive?.contentOrNull
                        val driverPhone = courierInfo?.get("driver_phone")?.jsonPrimitive?.contentOrNull
                        val plate       = courierInfo?.get("driver_plate_number")?.jsonPrimitive?.contentOrNull
                        Column {
                            if (driverName.isNullOrEmpty()) {
                                Text(
                                    "Kurir belum ditugaskan untuk pesanan ini. Coba cek lagi beberapa saat lagi.",
                                    fontSize = 13.sp, color = textSec, lineHeight = 18.sp
                                )
                            } else {
                                Text(driverName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = textPri)
                                if (!plate.isNullOrEmpty()) {
                                    Text("Plat: $plate", fontSize = 12.sp, color = textSec, modifier = Modifier.padding(top = 2.dp))
                                }
                                if (driverPhone.isNullOrEmpty()) {
                                    Text(
                                        "Nomor telepon kurir belum tersedia.",
                                        fontSize = 12.sp, color = textSec, modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val driverPhone = courierInfo?.get("driver_phone")?.jsonPrimitive?.contentOrNull
                val trackingLink = courierInfo?.get("tracking_link")?.jsonPrimitive?.contentOrNull
                if (!driverPhone.isNullOrEmpty()) {
                    TextButton(onClick = {
                        showCourierDialog = false
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone")))
                    }) { Text("Telepon Kurir", color = red, fontWeight = FontWeight.Bold) }
                } else if (!trackingLink.isNullOrEmpty()) {
                    TextButton(onClick = {
                        showCourierDialog = false
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trackingLink)))
                    }) { Text("Lacak Pengiriman", color = red, fontWeight = FontWeight.Bold) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCourierDialog = false }) { Text("Tutup", color = textSec) }
            }
        )
    }
}

// ─── Reusable Composables ─────────────────────────────────────────────────────
@Composable
private fun ModernCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 1.dp,
        content = { Column(modifier = Modifier.padding(14.dp), content = content) }
    )
}

@Composable
private fun CardHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFFC62828), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
    }
}

@Composable
private fun ModernInfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Color(0xFF888888))
        Text(
            value,
            fontSize = if (mono) 11.sp else 12.sp,
            fontWeight = if (mono) FontWeight.Bold else FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = if (mono) Color(0xFF1565C0) else Color(0xFF1A1A1A),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}