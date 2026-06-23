package com.example.app.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.app.network.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Data Classes ─────────────────────────────────────────────────────────────
data class PaymentOption(
    val id: String,
    val name: String,
    val logoUrl: String,
    val minAmount: Int = 0,
    val fee: Int = 0
)

@Serializable
data class CartItemJson(
    @SerialName("product_name")      val productName: String,
    @SerialName("product_image_url") val productImageUrl: String?,
    @SerialName("product_price")     val productPrice: Int,
    @SerialName("quantity")          val quantity: Int
)

@Serializable
data class TransactionInsertRequest(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("user_id")        val userId: String,
    @SerialName("address_id")     val addressId: String?,
    @SerialName("cart_items")     val cartItems: List<CartItemJson>,
    @SerialName("grand_total")    val grandTotal: Int,
    @SerialName("earned_points")  val earnedPoints: Int,
    @SerialName("status")         val status: String = "Menunggu Pembayaran",
    @SerialName("source")         val source: String = "aplikasi",
    @SerialName("courier_code")   val courierCode: String? = null,
    @SerialName("courier_type")   val courierType: String? = null,
    @SerialName("courier_name")   val courierName: String? = null,
    @SerialName("shipping_cost")  val shippingCost: Int? = null,
)

@Serializable
data class UserPointRecord(
    @SerialName("user_id") val userId: String,
    val balance: Int
)

@Serializable
data class TransactionResponse(
    val id: String,
    @SerialName("transaction_id") val transactionId: String
)

@Serializable
data class ProfileRecord(
    @SerialName("full_name")    val fullName: String?,
    @SerialName("phone_number") val phoneNumber: String?
)

@Serializable
data class TransactionStatusRecord(
    val status: String
)

@Serializable
data class AppConfigRecord(
    val key: String,
    val value: String
)

@Serializable
data class VoucherData(
    val id: String,
    @SerialName("nama_voucher") val namaVoucher: String,
    @SerialName("potongan_harga") val potonganHarga: Int,
    @SerialName("min_belanja") val minBelanja: Int,
    @SerialName("berlaku_hingga") val berlakuHingga: String
)

@Serializable
data class UserVoucherItem(
    val id: String,
    val vouchers: VoucherData? = null
)

// ─── PaymentMethodScreen ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMethodScreen(
    navController: NavController,
    totalTagihan: Int,
    addressId: String? = null,
    cartViewModel: CartViewModel = viewModel(),
    courierCode: String? = null,
    courierType: String? = null,
    courierName: String? = null,
    shippingCost: Int = 0,
) {
    val primaryRed     = Color(0xFFD32F2F)
    val backgroundGray = Color(0xFFF5F6F8)
    val formatRupiah   = NumberFormat.getCurrencyInstance(Locale("in", "ID"))
    val context        = LocalContext.current
    val cartItems      by cartViewModel.cartItems.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var poinMember       by remember { mutableIntStateOf(0) }
    var totalEarnedPoints by remember { mutableIntStateOf(0) }
    var isUsePoin        by remember { mutableStateOf(false) }
    var selectedMethod   by remember { mutableStateOf<PaymentOption?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }
    var isProcessing     by remember { mutableStateOf(false) }

    // State Voucher
    var userVouchers by remember { mutableStateOf<List<UserVoucherItem>>(emptyList()) }
    var selectedVoucher by remember { mutableStateOf<UserVoucherItem?>(null) }
    var showVoucherSheet by remember { mutableStateOf(false) }

    // Kalkulasi Total yang Diperbarui (Voucher didahulukan, baru Poin)
    val potonganVoucher = selectedVoucher?.vouchers?.potonganHarga ?: 0
    val sisaSetelahVoucher = (totalTagihan - potonganVoucher).coerceAtLeast(0)

    val maxPoinBisaDipakai = minOf(poinMember, sisaSetelahVoucher)
    val potonganPoin = if (isUsePoin) maxPoinBisaDipakai else 0
    val biayaAdmin   = selectedMethod?.fee ?: 0

    val grandTotal   = (sisaSetelahVoucher - potonganPoin + biayaAdmin).coerceAtLeast(0)

    // Deteksi jika tagihan sepenuhnya gratis
    val isFree = grandTotal == 0 && (potonganPoin > 0 || potonganVoucher > 0)

    val paymentMethods = listOf(
        PaymentOption(
            id = "qris",
            name = "QRIS",
            logoUrl = "https://www.image2url.com/r2/default/images/1781330830641-aa4b0639-dac4-4b7c-8baa-cf00e9f6c6f5.png",
            minAmount = 1
        ),
        PaymentOption(
            id = "mandiri_va",
            name = "Mandiri Virtual Account",
            logoUrl = "https://www.image2url.com/r2/default/images/1781765287363-174c1d2b-c274-47db-a255-d1635b528b66.png",
            minAmount = 50000
        ),
        PaymentOption(
            id = "bri_va",
            name = "BRI Virtual Account",
            logoUrl = "https://www.image2url.com/r2/default/images/1781760406373-95b8ae16-e258-45c7-a824-0beef38ef553.png",
            minAmount = 50000
        )
        // BCA Virtual Account dimatikan sementara — channel "bca_va" belum aktif
        // di akun Midtrans production. Aktifkan lagi setelah approval BCA selesai.
    )

    LaunchedEffect(Unit) {
        val user = SupabaseClient.client.auth.currentUserOrNull()
        if (user != null) {
            coroutineScope.launch {
                try {
                    // Ambil poin user
                    val pointData = SupabaseClient.client.from("user_points")
                        .select { filter { eq("user_id", user.id) } }
                        .decodeSingleOrNull<UserPointRecord>()
                    if (pointData != null) poinMember = pointData.balance

                    // Tarik Daftar Voucher yang belum dipakai
                    val voucherList = SupabaseClient.client.from("user_vouchers")
                        .select(columns = Columns.raw("id, vouchers(id, nama_voucher, potongan_harga, min_belanja, berlaku_hingga)")) {
                            filter {
                                eq("user_id", user.id)
                                eq("is_used", false)
                            }
                        }
                        .decodeList<UserVoucherItem>()
                    userVouchers = voucherList.filter { it.vouchers != null }

                    // Hitung potensi poin yang akan didapat dari pembelian
                    val skusInCart = cartItems.map { it.product.barcodeSku }
                    if (skusInCart.isNotEmpty()) {
                        val pointsData = SupabaseClient.client.from("promo_poin")
                            .select { filter { isIn("product_sku", skusInCart); eq("is_active", true) } }
                            .decodeList<CheckoutPromoPoin>()
                        var calculated = 0
                        cartItems.forEach { item ->
                            val pointValue = pointsData.find { it.product_sku == item.product.barcodeSku }?.nilai_poin ?: 0
                            calculated += (pointValue * item.quantity)
                        }
                        totalEarnedPoints = calculated
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    Scaffold(
        containerColor = primaryRed,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Metode Pembayaran", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = primaryRed)
            )
        },
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 16.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Total Tagihan", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text(formatRupiah.format(grandTotal), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = primaryRed)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        // ✅ Aktif kalau ada metode bayar ATAU gratis
                        onClick = { if (selectedMethod != null || isFree) showConfirmation = true },
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectedMethod == null && !isFree) Color.LightGray else primaryRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = selectedMethod != null || isFree
                    ) {
                        // ✅ Ganti teks jika gratis
                        Text(if (isFree) "Selesaikan Pesanan" else "Bayar Sekarang", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(backgroundGray)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ─ Voucher & Poin ──────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column {
                    // TOMBOL PILIH VOUCHER DARI DOMPET
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showVoucherSheet = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(36.dp).background(Color(0xFFE3F2FD), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedVoucher?.vouchers?.namaVoucher ?: "Gunakan Voucher",
                                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black
                            )
                            Text(
                                text = if (selectedVoucher != null) "Hemat ${formatRupiah.format(potonganVoucher)}" else "Tersedia ${userVouchers.size} voucher di dompetmu",
                                fontSize = 12.sp, color = if (selectedVoucher != null) primaryRed else Color.Gray
                            )
                        }
                        if (selectedVoucher != null) {
                            Icon(Icons.Rounded.Cancel, contentDescription = "Batal", tint = Color.Gray, modifier = Modifier.clickable { selectedVoucher = null })
                        } else {
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.Gray)
                        }
                    }
                    HorizontalDivider(color = Color(0xFFF1F3F4))

                    // TOMBOL TUKAR POIN
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(36.dp).background(Color(0xFFFFF8E1), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Stars, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tukar $maxPoinBisaDipakai A-Koin", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
                            Text(
                                if (maxPoinBisaDipakai > 0) "Hemat ${formatRupiah.format(maxPoinBisaDipakai)}"
                                else "Saldo Poin belum cukup",
                                fontSize = 12.sp, color = Color.Gray
                            )
                        }
                        Switch(
                            checked = isUsePoin,
                            onCheckedChange = { isUsePoin = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = primaryRed),
                            enabled = maxPoinBisaDipakai > 0
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ─ Label Metode ───────────────────────────────────────────
            Text(
                "Pilih Metode Pembayaran",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                color = Color.Black,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // ─ Daftar metode per kategori (QRIS & Virtual Account) ─────
            val methodDescriptions = mapOf(
                "qris"       to "Bayar dengan scan kode QR dari semua dompet digital & bank",
                "mandiri_va" to "Transfer ke nomor Virtual Account Mandiri, berlaku 24 jam",
                "bri_va"     to "Transfer ke nomor Virtual Account BRI, berlaku 24 jam"
            )

            // Card detail — dipakai untuk section QRIS (full width, ada deskripsi)
            @Composable
            fun PaymentMethodCard(method: PaymentOption, modifier: Modifier = Modifier) {
                val isEligible = grandTotal >= method.minAmount && !isFree
                val isSelected = selectedMethod?.id == method.id
                Card(
                    modifier = modifier
                        .clickable(enabled = isEligible) { selectedMethod = method }
                        .alpha(if (isEligible) 1f else 0.45f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFFFFF0F0) else Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) primaryRed else Color(0xFFEEEEEE)
                    ),
                    elevation = CardDefaults.cardElevation(if (isSelected) 4.dp else 1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    if (isSelected) Color.White else Color(0xFFF8F9FA),
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = method.logoUrl,
                                contentDescription = method.name,
                                modifier = Modifier.size(40.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                method.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isSelected) primaryRed else Color.Black
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                methodDescriptions[method.id] ?: "",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                lineHeight = 16.sp
                            )
                            if (!isEligible && !isFree) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Min. transaksi ${formatRupiah.format(method.minAmount)}",
                                    fontSize = 11.sp,
                                    color = primaryRed,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = primaryRed,
                                unselectedColor = Color(0xFFCCCCCC)
                            )
                        )
                    }
                }
            }

            // Card compact — dipakai untuk grid Virtual Account (logo + nama saja)
            @Composable
            fun PaymentMethodGridCard(method: PaymentOption, modifier: Modifier = Modifier) {
                val isEligible = grandTotal >= method.minAmount && !isFree
                val isSelected = selectedMethod?.id == method.id
                Card(
                    modifier = modifier
                        .clickable(enabled = isEligible) { selectedMethod = method }
                        .alpha(if (isEligible) 1f else 0.45f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFFFFF0F0) else Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) primaryRed else Color(0xFFEEEEEE)
                    ),
                    elevation = CardDefaults.cardElevation(if (isSelected) 4.dp else 1.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (isSelected) Color.White else Color(0xFFF8F9FA),
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = method.logoUrl,
                                contentDescription = method.name,
                                modifier = Modifier.size(34.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            method.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isSelected) primaryRed else Color.Black,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        if (!isEligible && !isFree) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Min. ${formatRupiah.format(method.minAmount)}",
                                fontSize = 10.sp,
                                color = primaryRed,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Icon(
                            if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) primaryRed else Color(0xFFCCCCCC),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            val qrisMethods = paymentMethods.filter { it.id == "qris" }
            val vaMethods    = paymentMethods.filter { it.id != "qris" }

            // ─ Section QRIS ─────────────────────────────────────────
            if (qrisMethods.isNotEmpty()) {
                Text(
                    "QRIS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    qrisMethods.forEach { method ->
                        PaymentMethodCard(method = method, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ─ Section Virtual Account — grid 2 kolom ───────────────
            if (vaMethods.isNotEmpty()) {
                Text(
                    "Transfer Bank / Virtual Account",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    vaMethods.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { method ->
                                PaymentMethodGridCard(method = method, modifier = Modifier.weight(1f))
                            }
                            if (rowItems.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ─ Banner panduan pembayaran ───────────────────────────
            val guideContent: Pair<String, List<String>>? = when (selectedMethod?.id) {
                "qris" -> Pair("📱 Cara Bayar QRIS", listOf(
                    "Buka aplikasi dompet digital atau mobile banking kamu",
                    "Pilih menu \"Bayar\" atau \"Scan QR\"",
                    "Scan kode QR yang muncul di halaman berikutnya",
                    "Periksa nominal, lalu konfirmasi pembayaran",
                    "Pesanan otomatis diproses setelah pembayaran berhasil"
                ))
                "bri_va" -> Pair("🏦 Cara Bayar BRI Virtual Account", listOf(
                    "Catat nomor Virtual Account yang muncul setelah klik Bayar",
                    "Buka BRImo / ATM BRI / Internet Banking BRI",
                    "Pilih menu BRIVA, lalu masukkan nomor VA yang didapat",
                    "Periksa nominal, lalu konfirmasi pembayaran",
                    "Konfirmasi — pesanan diproses otomatis dalam 1-5 menit"
                ))
                "mandiri_va" -> Pair("🏦 Cara Bayar Mandiri Virtual Account", listOf(
                    "Catat nomor Virtual Account yang muncul setelah klik Bayar",
                    "Buka Livin' by Mandiri / ATM Mandiri",
                    "Pilih Bayar → Multipayment → masukkan kode 70012",
                    "Masukkan nomor VA dan konfirmasi nominal",
                    "Konfirmasi — pesanan diproses otomatis dalam 1-5 menit"
                ))
                else -> null
            }

            if (guideContent != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F7FF)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFBBDEFB)),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(32.dp).background(Color(0xFF1976D2), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(guideContent.first, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color(0xFF0D47A1))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        guideContent.second.forEachIndexed { i, step ->
                            Row(modifier = Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier.size(20.dp).background(Color(0xFF1976D2), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${i + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(step, fontSize = 12.sp, color = Color(0xFF1565C0), lineHeight = 18.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ─ Banner keamanan ────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FFF4)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(32.dp).background(Color(0xFF059669), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("🛡️ Transaksi Aman & Terenkripsi", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color(0xFF065F46))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    listOf(
                        "Pembayaran diproses dengan sistem keamanan berlapis bersertifikasi internasional",
                        "Data rekening & kartu Anda tidak pernah disimpan di server kami",
                        "Dilindungi enkripsi SSL 256-bit"
                    ).forEach { item ->
                        Row(modifier = Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.Top) {
                            Text("✔️ ", fontSize = 12.sp)
                            Text(item, fontSize = 12.sp, color = Color(0xFF047857), lineHeight = 18.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    // ── BOTTOM SHEET DAFTAR VOUCHER ───────────────────────────────────────────
    if (showVoucherSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoucherSheet = false },
            containerColor = Color.White
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Pilih Voucher Tersedia", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))

                if (userVouchers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Kamu belum memiliki voucher", color = Color.Gray, fontSize = 14.sp)
                    }
                } else {
                    userVouchers.forEach { item ->
                        val voucher = item.vouchers!!
                        val isEligible = totalTagihan >= voucher.minBelanja
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clickable(enabled = isEligible) {
                                    selectedVoucher = item
                                    showVoucherSheet = false
                                }
                                .alpha(if (isEligible) 1f else 0.5f),
                            colors = CardDefaults.cardColors(containerColor = if (selectedVoucher?.id == item.id) Color(0xFFFFF0F0) else Color(0xFFF8F9FA)),
                            border = BorderStroke(1.dp, if (selectedVoucher?.id == item.id) primaryRed else Color(0xFFEEEEEE)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.LocalOffer, contentDescription = null, tint = primaryRed, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(voucher.namaVoucher, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.Black)
                                    Text("Potongan: ${formatRupiah.format(voucher.potonganHarga)}", fontSize = 13.sp, color = primaryRed, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Min. Belanja ${formatRupiah.format(voucher.minBelanja)}", fontSize = 11.sp, color = Color.Gray)
                                }
                                if (selectedVoucher?.id == item.id) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = primaryRed)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ── Dialog Konfirmasi + Proses Pembayaran ─────────────────────────────────
    if (showConfirmation && (selectedMethod != null || isFree)) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showConfirmation = false },
            title = {
                Text("Konfirmasi Pesanan", fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (isFree) {
                        Box(modifier = Modifier.size(56.dp).background(Color(0xFFE8F5E9), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Pesanan Gratis", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF4CAF50))
                        Text("Menggunakan Poin / Voucher", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        AsyncImage(model = selectedMethod!!.logoUrl, contentDescription = null, modifier = Modifier.height(40.dp), contentScale = ContentScale.Fit)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Lanjutkan pembayaran sebesar ", fontSize = 13.sp, color = Color.Gray)
                        Text(formatRupiah.format(grandTotal), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = primaryRed)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isProcessing = true
                            try {
                                val user = SupabaseClient.client.auth.currentUserOrNull()
                                if (user != null && cartItems.isNotEmpty()) {
                                    val dateFormat   = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                                    val generatedInvId = "INV/${dateFormat.format(Date())}/${System.currentTimeMillis() % 100000}"

                                    val mappedCartItems = cartItems.map {
                                        CartItemJson(
                                            productName     = it.product.name,
                                            productImageUrl = it.product.imageUrl,
                                            productPrice    = it.product.price.toInt(),
                                            quantity        = it.quantity
                                        )
                                    }
                                    val newTransaction = TransactionInsertRequest(
                                        transactionId = generatedInvId,
                                        userId        = user.id,
                                        addressId     = addressId,
                                        cartItems     = mappedCartItems,
                                        grandTotal    = grandTotal,
                                        earnedPoints  = totalEarnedPoints,
                                        // Jika gratis langsung status Diproses
                                        status        = if (isFree) "Diproses" else "Menunggu Pembayaran",
                                        courierCode   = courierCode,
                                        courierType   = courierType,
                                        courierName   = courierName,
                                        shippingCost  = shippingCost,
                                    )

                                    // 1. Insert transaksi
                                    val insertedTxList = SupabaseClient.client.from("transactions")
                                        .insert(newTransaction) { select() }
                                        .decodeList<TransactionResponse>()

                                    if (insertedTxList.isNotEmpty()) {
                                        val insertedTxId = insertedTxList.first().id
                                        // 2. Redeem poin jika dipakai
                                        if (isUsePoin && potonganPoin > 0) {
                                            val redeemBody = buildJsonObject {
                                                put("user_id",        user.id)
                                                put("transaction_id", insertedTxId)
                                                put("amount",         -potonganPoin)
                                                put("description",    "Tukar Poin untuk $generatedInvId")
                                                put("type",           "REDEEM")
                                            }
                                            SupabaseClient.client.from("point_history").insert(redeemBody)
                                        }
                                        // 3. Update status Voucher jadi terpakai (is_used = true)
                                        if (selectedVoucher != null) {
                                            SupabaseClient.client.from("user_vouchers")
                                                .update({ set("is_used", true) }) {
                                                    filter { eq("id", selectedVoucher!!.id) }
                                                }
                                        }

                                        // ✅ LOGIKA BYPASS JIKA GRATIS
                                        if (isFree) {
                                            cartViewModel.clearCart()
                                            showConfirmation = false
                                            navController.navigate("transaction_detail/${android.net.Uri.encode(generatedInvId)}") {
                                                popUpTo(0) { inclusive = true }
                                            }
                                        } else {
                                            // Jika tidak gratis, proses ke Midtrans
                                            try {
                                                val profile = try {
                                                    SupabaseClient.client.from("profiles")
                                                        .select { filter { eq("id", user.id) } }
                                                        .decodeSingleOrNull<ProfileRecord>()
                                                } catch (_: Exception) { null }

                                                val snapBody = buildJsonObject {
                                                    put("order_id",       generatedInvId)
                                                    put("gross_amount",   grandTotal)
                                                    put("payment_method", selectedMethod!!.id)
                                                    putJsonArray("items") {
                                                        // Item produk asli
                                                        cartItems.forEach { cartItem ->
                                                            add(buildJsonObject {
                                                                put("id",       cartItem.product.barcodeSku)
                                                                put("name",     cartItem.product.name)
                                                                put("price",    cartItem.product.price.toInt())
                                                                put("quantity", cartItem.quantity)
                                                            })
                                                        }
                                                        // Item diskon voucher (harga negatif agar sum = grandTotal)
                                                        if (potonganVoucher > 0) {
                                                            add(buildJsonObject {
                                                                put("id",       "VOUCHER_DISC")
                                                                put("name",     "Diskon Voucher")
                                                                put("price",    -potonganVoucher)
                                                                put("quantity", 1)
                                                            })
                                                        }
                                                        // Item diskon poin (harga negatif agar sum = grandTotal)
                                                        if (potonganPoin > 0) {
                                                            add(buildJsonObject {
                                                                put("id",       "POIN_DISC")
                                                                put("name",     "Tukar A-Koin")
                                                                put("price",    -potonganPoin)
                                                                put("quantity", 1)
                                                            })
                                                        }
                                                    }
                                                    put("customer_name",  profile?.fullName  ?: user.email ?: "Pelanggan")
                                                    put("customer_email", user.email         ?: "")
                                                    put("customer_phone", profile?.phoneNumber ?: "")
                                                }

                                                val resp = SupabaseClient.client.functions
                                                    .invoke("create-payment", body = snapBody)

                                                val snapToken = try {
                                                    val responseText = resp.bodyAsText()
                                                    kotlinx.serialization.json.Json.parseToJsonElement(responseText)
                                                        .jsonObject["token"]?.jsonPrimitive?.content ?: ""
                                                } catch (_: Exception) { "" }

                                                cartViewModel.clearCart()
                                                showConfirmation = false

                                                if (snapToken.isNotEmpty()) {
                                                    navController.navigate(
                                                        "midtrans_snap/${android.net.Uri.encode(snapToken)}/${android.net.Uri.encode(generatedInvId)}/$totalEarnedPoints"
                                                    )
                                                } else {
                                                    navController.navigate(
                                                        "payment_detail/${android.net.Uri.encode(selectedMethod!!.name)}/${android.net.Uri.encode(generatedInvId)}/$totalEarnedPoints?flip_url="
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                cartViewModel.clearCart()
                                                showConfirmation = false
                                                navController.navigate(
                                                    "payment_detail/${android.net.Uri.encode(selectedMethod!!.name)}/${android.net.Uri.encode(generatedInvId)}/$totalEarnedPoints?flip_url="
                                                )
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                // Tampilkan error asli untuk memudahkan debugging
                                val errorDetail = e.message ?: e.javaClass.simpleName
                                Toast.makeText(context, "Error: $errorDetail", Toast.LENGTH_LONG).show()
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text("Ya, Lanjutkan", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { if (!isProcessing) showConfirmation = false },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    border = BorderStroke(1.dp, Color.LightGray),
                    enabled = !isProcessing
                ) { Text("Batal", color = Color.Gray, fontWeight = FontWeight.Bold) }
            },
            containerColor = Color.White
        )
    }
}

// ─── MidtransSnapScreen ───────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MidtransSnapScreen(
    navController: NavController,
    snapToken: String,
    orderId: String,
    earnedPoints: Int
) {
    val primaryRed     = Color(0xFFD32F2F)
    var snapBaseUrl    by remember { mutableStateOf("") }
    var isLoading      by remember { mutableStateOf(true) }
    var isConfigLoaded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val config = SupabaseClient.client
                .from("app_config")
                .select { filter { eq("key", "midtrans_snap_base_url") } }
                .decodeSingleOrNull<AppConfigRecord>()
            snapBaseUrl = config?.value
                ?: "https://app.sandbox.midtrans.com/snap/v2/vtweb/"
        } catch (_: Exception) {
            snapBaseUrl = "https://app.sandbox.midtrans.com/snap/v2/vtweb/"
        } finally {
            isConfigLoaded = true
        }
    }

    suspend fun checkAndNavigate() {
        kotlinx.coroutines.delay(2000)
        try {
            val res = SupabaseClient.client.from("transactions")
                .select { filter { eq("transaction_id", orderId) } }
                .decodeSingleOrNull<TransactionStatusRecord>()
            val status = res?.status ?: ""
            if (status == "Diproses" || status == "Selesai") {
                navController.navigate("transaction_detail/${android.net.Uri.encode(orderId)}") {
                    popUpTo(0) { inclusive = true }
                }
            } else if (status == "Dibatalkan") {
                navController.popBackStack()
            }
        } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("Pembayaran", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = primaryRed)
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (!isConfigLoaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = primaryRed)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Menyiapkan pembayaran...", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled  = true
                            setDownloadListener { url, userAgent, _, mimeType, _ ->
                                try {
                                    val fileName = "QRIS-$orderId.png"
                                    val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                                        .addRequestHeader("User-Agent", userAgent)
                                        .setMimeType(if (mimeType.isNullOrBlank()) "image/png" else mimeType)
                                        .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setTitle("QRIS Pembayaran")
                                    val downloadManager = ctx.getSystemService(android.app.DownloadManager::class.java)
                                    downloadManager?.enqueue(request)
                                    Toast.makeText(ctx, "Mengunduh QRIS ke folder Download...", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Gagal mengunduh QRIS", Toast.LENGTH_SHORT).show()
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { isLoading = true }
                                override fun onPageFinished(view: WebView?, url: String?) { isLoading = false }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    // Custom URL scheme used as the Midtrans Snap payment return URL.
                                    // Must match the scheme declared in your AndroidManifest.xml
                                    // intent filter, and the Finish/Unfinish/Error redirect URLs
                                    // configured in your Midtrans dashboard.
                                    if (url.startsWith("myapp://")) {
                                        coroutineScope.launch { checkAndNavigate() }
                                        return true
                                    }
                                    return false
                                }
                            }
                            loadUrl("$snapBaseUrl$snapToken")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = primaryRed)
                    }
                }
            }
        }
    }

    LaunchedEffect(orderId) {
        try {
            val channel = SupabaseClient.client.channel("trx_$orderId")
            val flow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                table = "transactions"
            }
            coroutineScope.launch {
                flow.collect { change ->
                    val status = change.record["status"]?.jsonPrimitive?.content ?: ""
                    if (status == "Diproses" || status == "Selesai") {
                        navController.navigate("transaction_detail/${android.net.Uri.encode(orderId)}") {
                            popUpTo(0) { inclusive = true }
                        }
                    } else if (status == "Dibatalkan") {
                        navController.popBackStack()
                    }
                }
            }
            channel.subscribe()
        } catch (_: Exception) {}
    }
}