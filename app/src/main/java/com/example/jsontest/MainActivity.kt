package com.example.jsontest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.example.jsontest.databinding.ActivityMainBinding
import com.example.jsontest.retrofit.AirQaulityResponse
import com.example.jsontest.retrofit.AirQualityService
import com.example.jsontest.retrofit.RetrofitConnection
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.lang.IllegalArgumentException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding

    //런타임 권한 요청 시 필요한 요청 코드
    private val PERMISSION_REQUEST_CODE = 100
    //요청할 권한 목록
    var REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION)

    //==위치 서비스 요청 시 필요한 런처==
    lateinit var getGPSPermissionLauncher: ActivityResultLauncher<Intent>


    // 위도와 경도를 가져온다
    lateinit var locationProvider: LocationProvider


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAllPermission()
        updateUI()  //====
        setRefreshButton()
    }

    private fun setRefreshButton() {
        binding.btnRefresh.setOnClickListener {
            updateUI()
        }
    }

    private fun updateUI() {
        locationProvider = LocationProvider(this@MainActivity)

        //위도와 경도 정보 가져옴
        val latitude: Double = locationProvider.getLocationLatitude()
        val longitude: Double = locationProvider.getLocationLongitude()

        if(latitude != 0.0 || longitude != 0.0) {
            // 1. 현재 위치를 가져오고 UI 업데이트
            val address = getCurrentAddress(latitude, longitude)

            address?.let{
                binding.tvLocationTitle.text = "${it.thoroughfare}" //ex 삼산 2동
                binding.tvLocationSubtitle.text = "${it.countryName} ${it.adminArea}"   //ex 대한민국 인천시
            }

            // 2. 현재 미세먼지 농도 가져오고 UI 업데이트
            getAirQualityData(latitude, longitude)
        }
        else {
            Toast.makeText(
                this@MainActivity,
                "위도, 경도 정보를 가져올 수 없습니다. 새로고침을 눌러주세요!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // @desc 레트로핏 클래스를 이용하여 미세먼지 오염 정보를 가져옴
    private fun getAirQualityData(latitude: Double, longitude: Double) {
        val retrofitAPI = RetrofitConnection.getInstance().create(AirQualityService::class.java)    //레트로핏 객체를 이용해 AirQualityService 인터페이스 구현체를 가져옴

        retrofitAPI.getAirQualityData(
            latitude.toString(),
            longitude.toString(),
            "d07e9cec-8760-4f44-90cf-ff4bb5edfe3f"
        ).enqueue(object : Callback<AirQaulityResponse> {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onResponse(
                call: Call<AirQaulityResponse>,
                response: Response<AirQaulityResponse>
            ) {
                //정상적인 Respinse가 왔다면 UI 업데이트
                if(response.isSuccessful) {
                    Toast.makeText(this@MainActivity, "최신 정보 업데이트 완료!", Toast.LENGTH_SHORT).show()
                    //response.body()가 null이 아니면 updateAirUI()
                    response.body()?.let{updateAirUI(it)}
                }
                else{
                    Toast.makeText(this@MainActivity, "업데이트에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<AirQaulityResponse>, t: Throwable) {
                t.printStackTrace()
                Toast.makeText(this@MainActivity, "업데이트에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    //가져온 데이터 정보를 바탕으로 화면 업데이트
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateAirUI(airQualityData: AirQaulityResponse) {
        val pollutionData = airQualityData.data.current.pollution

        //수치 지정 (메인 화면 가운데 숫자)
        binding.tvCount.text = pollutionData.aqius.toString()

        //측정된 날짜 지정
        val dateTime = ZonedDateTime.parse(pollutionData.ts).withZoneSameInstant(
            ZoneId.of("Asia/Seoul")).toLocalDateTime()
        val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        binding.tvCheckTime.text = dateTime.format(dateFormatter).toString()

        when (pollutionData.aqius) {
            in 0..50 -> {
                binding.tvTitle.text = "좋음"
                binding.imgBg.setImageResource(R.drawable.bg_good)
            }
            in 51..150 -> {
                binding.tvTitle.text = "보통"
                binding.imgBg.setImageResource(R.drawable.bg_soso)
            }
            in 151..200 -> {
                binding.tvTitle.text = "나쁨"
                binding.imgBg.setImageResource(R.drawable.bg_bad)
            }
            else -> {
                binding.tvTitle.text = "매우 나쁨"
                binding.imgBg.setImageResource(R.drawable.bg_worst)
            }
        }
    }

    fun getCurrentAddress(latitude: Double, longitude: Double) : Address? {
        val geocoder = Geocoder(this, Locale.getDefault())
        val addresses: List<Address>?   //Address 객체는 주소와 관련된 여러 정보 가짐

        addresses = try {
            geocoder.getFromLocation(latitude, longitude, 7)    //Geocoder 객체를 이용하여 위도와 경도로부터 리스트 가져옴
        } catch (ioException: IOException) {
            Toast.makeText(this, "지오코더 서비스 사용불가!", Toast.LENGTH_LONG).show()
            return null
        } catch (illegalArgumentException: IllegalArgumentException) {
            Toast.makeText(this, "잘못된 위도, 경도 입니다!", Toast.LENGTH_LONG).show()
            return null
        }

        //에러는 없지만 주소가 발견되지 않은 경우
        if (addresses == null || addresses.size == 0) {
            Toast.makeText(this, "주소가 발견되지 않았습니다.", Toast.LENGTH_LONG).show()
            return null
        }

        val address: Address = addresses[0]
        return address
    }

    private fun checkAllPermission() {
        // 1. gps가 켜져있는지 확인
        if(!isLocationServicesAvailable()){
            showDialogForLocationServiceSetting();
        }
        else {
            //2. 런타임 앱 권한이 모두 허용되어 있는지 확인
            isRunTimePermissionsGranted();
        }
    }

    fun isLocationServicesAvailable(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        return (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
    }

    fun isRunTimePermissionsGranted() {
        //위치 퍼미션을 가지고 있는지 체크
        val hasFineLocationPermission = ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (hasFineLocationPermission != PackageManager.PERMISSION_GRANTED ||
                hasCoarseLocationPermission != PackageManager.PERMISSION_GRANTED) {
            //권한이 한개라도 없다면 퍼미션 요청
            ActivityCompat.requestPermissions(this@MainActivity,REQUIRED_PERMISSIONS,PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.size == REQUIRED_PERMISSIONS.size){
            //요청코드가 PERMISSION_REQUEST_CODE이고, 요청한 퍼미션 개수만큼 수신되었다면
            var checkResult = true

            //모든 퍼미션을 허용했는지 체크
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    checkResult = false
                    break
                }
            }
            if (checkResult) {
                //위칫값을 가져올 수 있음
                updateUI()
            }
            else{
                //퍼미션이 거부되었다면 앱 종료
                Toast.makeText(
                    this@MainActivity,
                    "퍼미션이 거부되었습니다. 앱을 다시 실행하여 퍼미션을 허용해주세요!",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun showDialogForLocationServiceSetting() {
        //런처를 이용하여 결과값을 반환해야 하는 인텐트를 실행
        getGPSPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()) {result ->
            //결과값을 받았을 때 로직
            if (result.resultCode == Activity.RESULT_OK) {
                //사용자가 gps를 활성화시켰는지 확인
                if (isLocationServicesAvailable()){
                    isRunTimePermissionsGranted()   //런타임 권한 확인
                }
                else {
                    //위치 서비스가 허용되지 않았다면 앱 종료
                    Toast.makeText(
                        this@MainActivity,
                        "위치 서비스를 사용할 수 없습니다.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }

        val builder: AlertDialog.Builder = AlertDialog.Builder(this@MainActivity)

        builder.setTitle("위치 서비스 비활성화") //제목 설정
        builder.setMessage("위치 서비스가 꺼져 있습니다. 설정해야 앱을 사용할 수 있습니다.") //내용설정

        builder.setCancelable(true) // 다이얼로그 창 바깥 터치 시 창 닫힘
        builder.setPositiveButton("설정", DialogInterface.OnClickListener{
            dialog, id->    //확인 버튼 설정
            val callGPSSettingIntent = Intent(
                Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                getGPSPermissionLauncher.launch(callGPSSettingIntent)
        })
        builder.setNegativeButton("취소", //취소버튼 설정
            DialogInterface.OnClickListener{
                dialog, id->
                dialog.cancel()
                Toast.makeText(this@MainActivity, "기기에서 위치서비스(GPS) 설정 후 사용해주세요!",
                Toast.LENGTH_LONG).show()
                finish()
            })
        builder.create().show() //다이얼로그 생성 및 보여주기
    }



}
