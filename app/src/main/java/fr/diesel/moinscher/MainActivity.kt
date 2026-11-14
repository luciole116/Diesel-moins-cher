package fr.diesel.moinscher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Station(
    val adresse: String,
    val ville: String,
    val cp: String,
    val lat: Double,
    val lon: Double,
    val prix: Double,
    val maj: String,
    val distanceKm: Double
)

class MainActivity : Activity() {

    companion object {
        private const val RAYON_KM = 150
        private const val MAX_RESULTATS = 50
        private const val PRIX_RECENTS_JOURS = 7 // ignore les prix non mis à jour depuis 7 jours
        private const val REQ_LOC = 42
        private const val API =
            "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/" +
                "prix-des-carburants-en-france-flux-instantane-v2/records"
    }

    private lateinit var bestPrice: TextView
    private lateinit var bestStation: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnGo: Button
    private lateinit var btnRefresh: Button
    private lateinit var list: ListView

    private val main = Handler(Looper.getMainLooper())
    private var stations: List<Station> = emptyList()
    private var locationListener: LocationListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bestPrice = findViewById(R.id.bestPrice)
        bestStation = findViewById(R.id.bestStation)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnGo = findViewById(R.id.btnGo)
        btnRefresh = findViewById(R.id.btnRefresh)
        list = findViewById(R.id.list)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> ouvrirNavigation(stations[pos]) }
        btnGo.setOnClickListener { stations.firstOrNull()?.let { ouvrirNavigation(it) } }
        btnRefresh.setOnClickListener { demarrer() }

        demarrer()
    }

    // ---------- Localisation ----------

    private fun demarrer() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC
            )
            return
        }
        chargement(true, "Recherche de votre position…")
        obtenirPosition()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) demarrer()
            else erreur("Autorisez la localisation pour trouver les stations autour de vous.")
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun obtenirPosition() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) {
            erreur("La localisation est désactivée. Activez-la puis appuyez sur Actualiser.")
            return
        }

        // Position récente connue (moins de 10 min) : utilisation immédiate
        val derniere = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (derniere != null && System.currentTimeMillis() - derniere.time < 10 * 60 * 1000) {
            chercherStations(derniere)
            return
        }

        // Sinon on demande une position fraîche
        var recu = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (recu) return
                recu = true
                lm.removeUpdates(this)
                chercherStations(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        locationListener = listener
        for (p in providers) {
            if (p == LocationManager.PASSIVE_PROVIDER) continue
            runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
        }
        // Délai max : on se rabat sur la dernière position connue, même ancienne
        main.postDelayed({
            if (!recu) {
                recu = true
                lm.removeUpdates(listener)
                if (derniere != null) chercherStations(derniere)
                else erreur("Impossible d'obtenir votre position. Réessayez à l'extérieur.")
            }
        }, 20_000)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        locationListener?.let {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it)
        }
    }

    // ---------- API prix-carburants (data.economie.gouv.fr) ----------

    private fun chercherStations(pos: Location) {
        chargement(true, "Recherche des prix dans un rayon de $RAYON_KM km…")
        Thread {
            try {
                val resultat = appelerApi(pos.latitude, pos.longitude)
                main.post { afficher(resultat) }
            } catch (e: Exception) {
                main.post { erreur("Erreur réseau : ${e.message ?: "inconnue"}") }
            }
        }.start()
    }

    private fun appelerApi(lat: Double, lon: Double): List<Station> {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -PRIX_RECENTS_JOURS) }
        val depuis = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val point = String.format(Locale.US, "POINT(%.6f %.6f)", lon, lat)

        val where = "gazole_prix is not null" +
            " and gazole_maj >= date'$depuis'" +
            " and within_distance(geom, geom'$point', ${RAYON_KM}km)"

        val url = API +
            "?select=" + enc("id,adresse,ville,cp,geom,gazole_prix,gazole_maj") +
            "&where=" + enc(where) +
            "&order_by=" + enc("gazole_prix asc") +
            "&limit=$MAX_RESULTATS"

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw Exception("HTTP $code ${err.take(150)}")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val results = JSONObject(body).getJSONArray("results")
            val out = ArrayList<Station>()
            for (i in 0 until results.length()) {
                val o = results.getJSONObject(i)
                val geom = o.optJSONObject("geom") ?: continue
                val sLat = geom.optDouble("lat")
                val sLon = geom.optDouble("lon")
                val prix = o.optDouble("gazole_prix")
                if (sLat.isNaN() || sLon.isNaN() || prix.isNaN() || prix <= 0) continue
                out.add(
                    Station(
                        adresse = o.optString("adresse").ifBlank { "Adresse inconnue" }.capitaliser(),
                        ville = o.optString("ville").capitaliser(),
                        cp = o.optString("cp"),
                        lat = sLat,
                        lon = sLon,
                        prix = prix,
                        maj = o.optString("gazole_maj"),
                        distanceKm = distanceKm(lat, lon, sLat, sLon)
                    )
                )
            }
            // Tri par prix, puis par distance en cas d'égalité
            return out.sortedWith(compareBy({ it.prix }, { it.distanceKm }))
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun String.capitaliser(): String =
        lowercase(Locale.FRANCE).split(" ").joinToString(" ") { mot ->
            mot.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.FRANCE) else it.toString() }
        }

    // ---------- Affichage ----------

    private fun prixTexte(p: Double) = String.format(Locale.FRANCE, "%.3f €", p)

    private fun majTexte(maj: String): String {
        // Format reçu : 2026-11-14T09:30:00+00:00 → "14/11 09:30"
        if (maj.length < 16) return ""
        return "${maj.substring(8, 10)}/${maj.substring(5, 7)} ${maj.substring(11, 16)}"
    }

    private fun afficher(res: List<Station>) {
        stations = res
        adapter.notifyDataSetChanged()
        progress.visibility = View.GONE
        btnRefresh.isEnabled = true
        val best = res.firstOrNull()
        if (best == null) {
            bestPrice.text = "—"
            bestStation.text = "Aucune station avec un prix diesel récent dans un rayon de $RAYON_KM km."
            btnGo.isEnabled = false
            status.text = ""
            return
        }
        bestPrice.text = prixTexte(best.prix)
        bestStation.text = "${best.adresse}, ${best.cp} ${best.ville}\n" +
            String.format(Locale.FRANCE, "à %.0f km (vol d'oiseau)", best.distanceKm)
        btnGo.isEnabled = true
        status.text = "${res.size} stations les moins chères · prix officiels prix-carburants.gouv.fr"
    }

    private fun chargement(on: Boolean, msg: String) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        btnRefresh.isEnabled = !on
        status.text = msg
    }

    private fun erreur(msg: String) {
        progress.visibility = View.GONE
        btnRefresh.isEnabled = true
        status.text = msg
        if (stations.isEmpty()) bestStation.text = msg
    }

    private fun ouvrirNavigation(s: Station) {
        val label = Uri.encode("${s.adresse}, ${s.ville}")
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}($label)"))
        try {
            startActivity(geo)
        } catch (e: Exception) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lon}")
                )
            )
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = stations.size
        override fun getItem(position: Int) = stations[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_station, parent, false)
            val s = stations[position]
            v.findViewById<TextView>(R.id.rank).text = "${position + 1}"
            v.findViewById<TextView>(R.id.title).text = "${s.ville} (${s.cp})"
            v.findViewById<TextView>(R.id.subtitle).text = String.format(
                Locale.FRANCE, "%s\n%.0f km · maj %s", s.adresse, s.distanceKm, majTexte(s.maj)
            )
            v.findViewById<TextView>(R.id.price).text = prixTexte(s.prix)
            return v
        }
    }
}
