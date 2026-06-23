package com.example.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.app.network.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.osmdroid.config.Configuration
import java.text.NumberFormat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

@Serializable
data class CheckoutPromoPoin(
    val product_sku: String,
    val nilai_poin: Int,
    val is_active: Boolean = true
)
data class CourierOption(
    val id: String,
    val name: String,
    val logoUrl: String,
    val price: Double = 0.0,             // harga dari Biteship (realtime)
    val minDay: Int = 0,
    val maxDay: Int = 0,
    val serviceType: String = "",
    val courierCode: String = "",        // contoh: "gojek", "grab" — dipakai untuk Order API
    // Legacy fields (tidak dipakai lagi, tapi agar tidak break kode lama)
    val basePrice: Double = 0.0,
    val pricePerKm: Double = 0.0
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    navController: NavController,
    cartViewModel: CartViewModel,
    storeId: String? = null
) {
    val context = LocalContext.current
    val primaryRed = Color(0xFFD32F2F)
    val pointGold = Color(0xFFFFA000)
    val backgroundGray = Color(0xFFF5F7FA)
    val cardBlue = Color(0xFF0056A6)
    val formatRupiah = NumberFormat.getCurrencyInstance(Locale("in", "ID"))
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }
    val cartItems by cartViewModel.cartItems.collectAsState()
    val subTotal: Double = cartItems.sumOf { it.product.price * it.quantity.toDouble() }
    var addressList by remember { mutableStateOf<List<AddressRecord>>(emptyList()) }
    var selectedAddress by remember { mutableStateOf<AddressRecord?>(null) }
    var allStores by remember { mutableStateOf<List<StoreRecord>>(emptyList()) }
    var activeStore by remember { mutableStateOf<StoreRecord?>(null) }
    var isLoadingData by remember { mutableStateOf(true) }
    var promoPoinList by remember { mutableStateOf<List<CheckoutPromoPoin>>(emptyList()) }
    var totalEarnedPoints by remember { mutableIntStateOf(0) }
    var showAddressSheet by remember { mutableStateOf(false) }
    var showTimePickerSheet by remember { mutableStateOf(false) }
    var showStoreSelectionSheet by remember { mutableStateOf(false) }
    var isDelivery by remember { mutableStateOf(true) }
    var selectedTimeSlot by remember { mutableStateOf<String?>(null) }
    var selectedCourier by remember { mutableStateOf<CourierOption?>(null) }
    var dynamicShippingCost by remember { mutableDoubleStateOf(0.0) }
    var shippingDistanceKm by remember { mutableDoubleStateOf(0.0) }
    var isCalculatingShipping by remember { mutableStateOf(false) }
    // Kurir dari Biteship (realtime) — diisi saat user pilih alamat
    var availableCouriers by remember { mutableStateOf<List<CourierOption>>(emptyList()) }
    var isBiteshipLoading by remember { mutableStateOf(false) }
    // Popup reminder nomor HP — muncul sekali saat pertama buka checkout
    var showPhoneReminder by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        isLoadingData = true
        val user = SupabaseClient.client.auth.currentUserOrNull()
        try {
            // MENGGUNAKAN TEBAKAN TERKUAT: barcodeSku
            val skusInCart = cartItems.map { it.product.barcodeSku }
            coroutineScope.launch {
                val storesDeferred = async {
                    SupabaseClient.client.from("stores").select { filter { eq("is_active", true) } }.decodeList<StoreRecord>()
                }
                val addressDeferred = async {
                    if (user != null) {
                        SupabaseClient.client.from("addresses")
                            .select { filter { eq("user_id", user.id) } }
                            .decodeList<AddressRecord>()
                            .sortedByDescending { it.isPrimary }
                    } else emptyList()
                }
                val pointsDeferred = async {
                    SupabaseClient.client.from("promo_poin")
                        .select { filter { eq("is_active", true) } }
                        .decodeList<CheckoutPromoPoin>()
                }
                val stores = storesDeferred.await()
                allStores = stores
                activeStore = if (storeId != null) stores.find { it.id == storeId } ?: stores.firstOrNull { it.isMainBranch } else stores.firstOrNull { it.isMainBranch } ?: stores.firstOrNull()
                val addresses = addressDeferred.await()
                addressList = addresses
                if (addresses.isNotEmpty()) selectedAddress = addresses.first()
                val allActivePoints = pointsDeferred.await()
                promoPoinList = allActivePoints
                var calculatedPoints = 0
                cartItems.forEach { item ->
                    // MENGGUNAKAN TEBAKAN TERKUAT: barcodeSku
                    val pointValue = allActivePoints.find { it.product_sku == item.product.barcodeSku }?.nilai_poin ?: 0
                    calculatedPoints += (pointValue * item.quantity)
                }
                totalEarnedPoints = calculatedPoints
                isLoadingData = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isLoadingData = false
        }
    }
    // Hitung jarak toko ke alamat user (Haversine)
    fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon/2) * Math.sin(dLon/2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
        return r * c
    }

    // Fetch rates Biteship saat alamat berubah
    LaunchedEffect(selectedAddress, isDelivery, activeStore) {
        if (!isDelivery || activeStore == null || selectedAddress?.latitude == null) {
            availableCouriers = emptyList()
            selectedCourier = null
            dynamicShippingCost = 0.0
            return@LaunchedEffect
        }
        coroutineScope.launch {
            isBiteshipLoading = true
            selectedCourier = null
            dynamicShippingCost = 0.0

            // Hitung jarak realtime toko → alamat user
            if (activeStore != null && selectedAddress?.latitude != null) {
                shippingDistanceKm = calculateDistanceKm(
                    activeStore!!.latitude, activeStore!!.longitude,
                    selectedAddress!!.latitude!!, selectedAddress!!.longitude!!
                )
            }

            try {
                val session = SupabaseClient.client.auth.currentSessionOrNull()
                val token = session?.accessToken ?: ""

                // Build items array dari cart
                val itemsArr = JSONArray()
                cartItems.forEach { item ->
                    val obj = JSONObject()
                    obj.put("name", item.product.name)
                    obj.put("value", item.product.price.toInt())
                    obj.put("weight", 500)   // default 500g per item
                    obj.put("quantity", item.quantity)
                    itemsArr.put(obj)
                }

                val bodyJson = JSONObject()
                bodyJson.put("destination_latitude", selectedAddress!!.latitude)
                bodyJson.put("destination_longitude", selectedAddress!!.longitude)
                bodyJson.put("items", itemsArr)
                bodyJson.put("couriers", "gojek,grab")

                val resJson = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val reqBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("https://YOUR_PROJECT_REF.supabase.co/functions/v1/biteship-rates")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Bearer $token")
                        .post(reqBody)
                        .build()
                    val response = client.newCall(request).execute()
                    val resBody = response.body?.string() ?: "{}"
                    JSONObject(resBody)
                }

                if (resJson.optBoolean("success") && resJson.has("pricing")) {
                    val pricing = resJson.getJSONArray("pricing")
                    val courierList = mutableListOf<CourierOption>()
                    for (i in 0 until pricing.length()) {
                        val p = pricing.getJSONObject(i)
                        val courierCode = p.optString("courier_code", "")
                        // Biaya layanan flat +Rp1.000 — ditempel langsung ke ongkir yang
                        // ditampilkan, supaya angka konsisten dari awal checkout sampai
                        // konfirmasi bayar (gak berubah lagi di layar pilih metode bayar).
                        // Harga ASLI yang dipotong dari saldo Biteship saat order
                        // benar-benar dibuat tetap ditentukan sendiri oleh Biteship —
                        // markup ini murni jadi margin tambahan untuk toko.
                        val hargaAsli = p.optDouble("price", 0.0)
                        val hargaMarkup = hargaAsli + 1000.0
                        val logoUrl = when {
                            courierCode.contains("gosend") || courierCode.contains("gojek") ->
                                "https://www.image2url.com/r2/default/images/1781845642188-adfe6423-56ad-4935-94ba-c31c6e61d4a5.png"
                            courierCode.contains("grab") ->
                                "https://www.image2url.com/r2/default/images/1781845709587-f83e217f-3118-4b33-9984-db1e8831e48b.png"
                            courierCode.contains("jne") ->
                                "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3c/JNE_logo.svg/320px-JNE_logo.svg.png"
                            courierCode.contains("jnt") ->
                                "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e4/J%26T_Express.svg/320px-J%26T_Express.svg.png"
                            else -> ""
                        }
                        courierList.add(CourierOption(
                            id          = "${courierCode}_$i",
                            name        = "${p.optString("courier_name")} - ${p.optString("courier_service")}",
                            logoUrl     = logoUrl,
                            price       = hargaMarkup.toDouble(),
                            minDay      = p.optInt("min_day", 0),
                            maxDay      = p.optInt("max_day", 0),
                            serviceType = p.optString("service_type", ""),
                            courierCode = courierCode,
                        ))
                    }
                    availableCouriers = courierList
                } else {
                    availableCouriers = emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("Biteship", "Error: ${e.message}")
                availableCouriers = emptyList()
            }
            isBiteshipLoading = false
        }
    }

    // Update ongkir saat kurir dipilih
    LaunchedEffect(selectedCourier) {
        dynamicShippingCost = selectedCourier?.price ?: 0.0
    }
    val grandTotal: Double = subTotal + dynamicShippingCost
    val isReadyToPay = if (isDelivery) selectedTimeSlot != null && selectedCourier != null && selectedAddress != null && !isBiteshipLoading else selectedTimeSlot != null && activeStore != null
    // ── Popup reminder nomor HP ──────────────────────────────────────────────
    if (showPhoneReminder) {
        AlertDialog(
            onDismissRequest = { showPhoneReminder = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Box(
                    modifier = Modifier.size(56.dp).background(Color(0xFFFFEBEE), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Phone, contentDescription = null,
                        tint = Color(0xFFD32F2F), modifier = Modifier.size(28.dp))
                }
            },
            title = {
                Text(
                    "Pastikan Nomor HP Benar",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = Color.Black
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Driver GoJek/Grab akan menghubungi Anda via telepon atau WhatsApp jika membutuhkan konfirmasi lokasi pengiriman.",
                        fontSize = 14.sp,
                        color = Color(0xFF555555),
                        lineHeight = 22.sp
                    )
                    Surface(
                        color = Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Info, contentDescription = null,
                                tint = Color(0xFFE65100), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Pastikan nomor HP di alamat pengiriman aktif dan bisa dihubungi.",
                                fontSize = 12.sp,
                                color = Color(0xFF5D4037),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPhoneReminder = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Mengerti, Lanjutkan",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 4.dp))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPhoneReminder = false
                        navController.navigate("address")
                    }
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null,
                        tint = Color(0xFFD32F2F), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ubah Nomor HP", color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundGray)) {
        if (isLoadingData) {
            Box(
                modifier = Modifier.fillMaxSize().background(backgroundGray).statusBarsPadding(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = primaryRed) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(backgroundGray),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                // Header — statusBarsPadding() memastikan konten tidak tertimpa status bar
                item {
                    Surface(color = Color.White, shadowElevation = 4.dp) {
                        Box(
                            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                                .padding(horizontal = 4.dp).height(56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Pengiriman", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Kembali")
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // ── Delivery / Pickup Toggle ─────────────────────────
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f).height(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isDelivery) primaryRed else Color.Transparent)
                                    .clickable { isDelivery = true; selectedCourier = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.DeliveryDining, null,
                                        tint = if (isDelivery) Color.White else Color.Gray,
                                        modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Delivery", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                        color = if (isDelivery) Color.White else Color.Gray)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f).height(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (!isDelivery) primaryRed else Color.Transparent)
                                    .clickable { isDelivery = false; selectedCourier = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Store, null,
                                        tint = if (!isDelivery) Color.White else Color.Gray,
                                        modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Pickup", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                        color = if (!isDelivery) Color.White else Color.Gray)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    Column(modifier = Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                        Text(if (isDelivery) "Dikirim dari Cabang" else "Lokasi Pengambilan (Toko)", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.Black)
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(color = cardBlue, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable { showStoreSelectionSheet = true }) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(activeStore?.name ?: "Pilih Cabang Toko", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!isDelivery) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(activeStore?.fullAddress ?: "", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Ganti Toko", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    if (isDelivery) {
                        Column(modifier = Modifier.background(Color.White).padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Alamat Pengiriman", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.Black)
                                Text("Ganti", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primaryRed, modifier = Modifier.clickable { showAddressSheet = true })
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            if (selectedAddress == null) {
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFFFB74D))) {
                                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                                        Text("Alamat Kosong", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                                        Text("Harap tambahkan alamat pengiriman terlebih dahulu.", fontSize = 13.sp, color = Color.DarkGray)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(onClick = { navController.navigate("address") }, colors = ButtonDefaults.buttonColors(containerColor = primaryRed), shape = RoundedCornerShape(8.dp)) { Text("Tambah Alamat", fontSize = 13.sp) }
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = primaryRed, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${selectedAddress!!.recipientName} ", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Color.Black)
                                            Text("(${selectedAddress!!.label})", fontSize = 13.sp, color = Color.Gray)
                                        }
                                        Text(selectedAddress!!.phoneNumber, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                                        Text(selectedAddress!!.fullAddress, fontSize = 14.sp, color = Color.DarkGray, lineHeight = 20.sp)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                item {
                    Column(modifier = Modifier.background(Color.White).padding(16.dp)) {
                        Text(if (isDelivery) "Pilih Waktu Pengiriman" else "Pilih Waktu Pengambilan", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.Black)
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { showTimePickerSheet = true },
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, if (selectedTimeSlot == null) primaryRed else Color(0xFFE0E0E0)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AccessTimeFilled, contentDescription = null, tint = if (selectedTimeSlot == null) primaryRed else Color(0xFF1976D2), modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (isDelivery) "Kapan pesanan diantar?" else "Kapan pesanan diambil?", fontSize = 12.sp, color = Color.Gray)
                                    Text(selectedTimeSlot ?: "Ketuk untuk memilih waktu", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (selectedTimeSlot == null) primaryRed else Color.Black)
                                }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.Gray)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    AnimatedVisibility(visible = isDelivery && selectedAddress?.latitude != null && activeStore != null, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(modifier = Modifier.background(Color.White).padding(16.dp).fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Pilih Kurir Instan", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.Black)
                                if (shippingDistanceKm > 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.LocationOn, contentDescription = null,
                                            tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            String.format("%.1f km dari toko", shippingDistanceKm),
                                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                            color = Color(0xFF4CAF50)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            availableCouriers.forEach { courier ->
                                val isSelected = selectedCourier?.id == courier.id
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { selectedCourier = courier },
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFFFFF4F4) else Color.White),
                                    border = BorderStroke(1.dp, if (isSelected) primaryRed else Color(0xFFE0E0E0)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AsyncImage(model = courier.logoUrl, contentDescription = null, modifier = Modifier.width(48.dp).height(24.dp), contentScale = ContentScale.Fit)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(courier.name, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.Black)
                                            Text(
                                                formatRupiah.format(courier.price),
                                                fontSize = 13.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium
                                            )
                                            if (courier.minDay > 0) {
                                                Text(
                                                    "Estimasi ${courier.minDay}-${courier.maxDay} hari",
                                                    fontSize = 11.sp, color = Color.Gray
                                                )
                                            }
                                        }
                                        RadioButton(selected = isSelected, onClick = { selectedCourier = courier }, colors = RadioButtonDefaults.colors(selectedColor = primaryRed))
                                    }
                                }
                            }
                        }
                    }
                    if (isDelivery && selectedTimeSlot != null && selectedAddress?.latitude != null) Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    Column(modifier = Modifier.background(Color.White)) {
                        // ── Header Ringkasan Pesanan
                        Text(
                            "Ringkasan Pesanan",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = Color.Black,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
                        )
                        // ── Item-item produk langsung di sini (tidak pakai items{} terpisah)
                        cartItems.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = item.product.imageUrl ?: "",
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFFEEEEEE), RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.product.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.Black)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(formatRupiah.format(item.product.price), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = primaryRed)
                                }
                                Text("${item.quantity}x", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color.Gray)
                            }
                            if (index < cartItems.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFF5F5F5))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                item {
                    if (totalEarnedPoints > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            color = Color(0xFFFFF8E1),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.5.dp, pointGold)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(45.dp).background(pointGold, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Stars, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text("Reward Transaksi", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pointGold)
                                    Text("Kamu akan mendapatkan", fontSize = 13.sp, color = Color.Black)
                                    Text("$totalEarnedPoints Poin", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF795548))
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.background(Color.White).padding(20.dp)) {
                        Text("Rincian Pembayaran", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.Black)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal Produk", fontSize = 14.sp, color = Color.Gray)
                            Text(formatRupiah.format(subTotal), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Black)
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        if (isDelivery) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Ongkos Kirim", fontSize = 14.sp, color = Color.Gray)
                                if (isCalculatingShipping) {
                                    Text("...", fontSize = 14.sp, color = Color.Black)
                                } else {
                                    Text(if (selectedCourier != null) formatRupiah.format(dynamicShippingCost) else "-", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Black)
                                }
                            }
                        }
                        if (totalEarnedPoints > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Bonus Poin", color = pointGold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("+$totalEarnedPoints Poin", color = pointGold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Total Pembayaran", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                            if (isCalculatingShipping) {
                                Text("...", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = primaryRed)
                            } else {
                                Text(formatRupiah.format(grandTotal), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = primaryRed)
                            }
                        }
                    }
                }
            }
        }
        // BottomBar overlay — persis pola yang sama dengan HomeScreen
        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            color = Color.White,
            shadowElevation = 16.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Total Tagihan", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                    if (isCalculatingShipping) Text("Menghitung...", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = primaryRed)
                    else Text(formatRupiah.format(grandTotal), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = primaryRed)
                }
                Button(
                    onClick = {
                        if (isReadyToPay) {
                            val passedAddressId = if (isDelivery && selectedAddress != null) selectedAddress!!.id else "pickup"
                            // Kirim data kurir terpilih (kosong kalau ambil di toko) supaya
                            // tersimpan di transactions dan bisa dipakai bikin order Biteship
                            // otomatis setelah pembayaran sukses.
                            val courierCode = if (isDelivery) selectedCourier?.courierCode ?: "" else ""
                            val courierType = if (isDelivery) selectedCourier?.serviceType ?: "" else ""
                            val courierName = if (isDelivery) selectedCourier?.name ?: "" else ""
                            val shippingCost = if (isDelivery) dynamicShippingCost.toInt() else 0
                            navController.navigate(
                                "payment_method/${grandTotal.toInt()}/$passedAddressId" +
                                        "?courier_code=${android.net.Uri.encode(courierCode)}" +
                                        "&courier_type=${android.net.Uri.encode(courierType)}" +
                                        "&courier_name=${android.net.Uri.encode(courierName)}" +
                                        "&shipping_cost=$shippingCost"
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isReadyToPay) primaryRed else Color(0xFFE0E0E0)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(50.dp).width(150.dp),
                    enabled = isReadyToPay
                ) {
                    Text("Lanjut Bayar", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = if (isReadyToPay) Color.White else Color.Gray)
                }
            }
        }
    }
    StoreSelectionBottomSheet(
        showSheet = showStoreSelectionSheet, onDismiss = { showStoreSelectionSheet = false },
        userAddress = selectedAddress, allStores = allStores, activeStore = activeStore,
        onStoreSelected = { activeStore = it }, isDeliveryMode = isDelivery, onModeChange = { isDelivery = it; if (it) selectedCourier = null }
    )
    if (showAddressSheet) {
        ModalBottomSheet(onDismissRequest = { showAddressSheet = false }, containerColor = Color.White) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text("Pilih Alamat Pengiriman", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(16.dp))

                if (addressList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("Belum ada daftar alamat tersimpan.", color = Color.Gray) }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(addressList) { address ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { selectedAddress = address; showAddressSheet = false },
                                colors = CardDefaults.cardColors(containerColor = if (selectedAddress?.id == address.id) Color(0xFFFFF4F4) else Color.White),
                                border = BorderStroke(1.dp, if (selectedAddress?.id == address.id) primaryRed else Color(0xFFEEEEEE)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(address.label, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.Black)
                                        if (address.isPrimary) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(color = primaryRed, shape = RoundedCornerShape(4.dp)) { Text("Utama", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(address.fullAddress, fontSize = 13.sp, color = Color.Gray, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showAddressSheet = false; navController.navigate("address") }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = primaryRed), shape = RoundedCornerShape(12.dp)) { Text("+ Tambah Alamat Baru", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    if (showTimePickerSheet) {
        ModalBottomSheet(onDismissRequest = { showTimePickerSheet = false }, containerColor = Color.White) {
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            var selectedDayIndex by remember { mutableIntStateOf(0) }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text("Pilih Waktu", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedDayIndex == 0,
                        onClick = { selectedDayIndex = 0 },
                        label = { Text("Hari Ini", fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFEBEE), selectedLabelColor = primaryRed)
                    )
                    FilterChip(
                        selected = selectedDayIndex == 1,
                        onClick = { selectedDayIndex = 1 },
                        label = { Text("Besok", fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFEBEE), selectedLabelColor = primaryRed)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Toko tutup jam 02:00 — slot "Tengah Malam" memakai marker 26
                // (24 + 2) supaya tetap terhitung "belum lewat" sepanjang hari ini
                // (jam berapa pun, 0-23 selalu < 26), termasuk saat lewat tengah
                // malam (00:00-02:00) yang masih bagian dari jam operasional.
                val allSlots = listOf(
                    12 to "Pagi (08:00 - 12:00)",
                    15 to "Siang (12:00 - 15:00)",
                    18 to "Sore (15:00 - 18:00)",
                    21 to "Malam (18:00 - 21:00)",
                    26 to "Tengah Malam (21:00 - 02:00)"
                )
                val availableSlots = if (selectedDayIndex == 0) allSlots.filter { currentHour < it.first }.map { it.second } else allSlots.map { it.second }
                val dayPrefix = if (selectedDayIndex == 0) "Hari ini" else "Besok"
                // Toko buka 08:00-02:00 — "Antar/Ambil Sekarang" cuma ditawarkan
                // kalau lagi dalam jam operasional (di luar 02:00-08:00).
                val isStoreOpenNow = currentHour !in 2..7
                if (selectedDayIndex == 0 && isStoreOpenNow) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                selectedTimeSlot = "Sekarang"
                                showTimePickerSheet = false
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = primaryRed, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(if (isDelivery) "ANTAR SEKARANG" else "AMBIL SEKARANG", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = primaryRed)
                            Text("Langsung diproses tanpa dijadwalkan", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    HorizontalDivider(color = Color(0xFFEEEEEE))
                }
                if (availableSlots.isEmpty()) {
                    Text("Tidak ada jadwal tersedia untuk hari ini. Silakan pilih 'Besok'.", color = Color.Gray, fontSize = 13.sp)
                } else {
                    availableSlots.forEach { slot ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedTimeSlot = "$dayPrefix, $slot"
                                showTimePickerSheet = false
                            }.padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = primaryRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(slot, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                        HorizontalDivider(color = Color(0xFFEEEEEE))
                    }
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}
