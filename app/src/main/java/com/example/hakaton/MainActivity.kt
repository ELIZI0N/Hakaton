
package com.example.hakaton

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var uploadButton: Button
    private lateinit var resultTextView: TextView
    private lateinit var recommendedImageView: ImageView
    private var photoUri: Uri? = null
    private lateinit var takePictureLauncher: ActivityResultLauncher<Intent>
    private lateinit var selectPictureLauncher: ActivityResultLauncher<Intent>
    private var currentPhotoPath: String? = null

    private val ovalFrames = R.drawable.oval_frames
    private val roundFrames = R.drawable.round_frames
    private val squareFrames = R.drawable.square_frames

    private var analysisPerformed = false

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("MainActivity", "Camera permission granted")
                dispatchTakePictureIntent()
            } else {
                Log.i("MainActivity", "Camera permission denied")
                Toast.makeText(
                    this,
                    "Camera permission is required to take photos.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("MainActivity", "Получено разрешение на хранение")
                selectImageFromGallery() // Launch gallery if permission granted
            } else {
                Log.i("MainActivity", "Отказано в разрешении на хранение")
                Toast.makeText(
                    this,
                    "Для доступа к галерее требуется разрешение на хранение.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        uploadButton = findViewById(R.id.uploadButton)
        resultTextView = findViewById(R.id.resultTextView)
        recommendedImageView = findViewById(R.id.recommendedImageView)

        takePictureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    currentPhotoPath?.let { path ->
                        val file = File(path)
                        photoUri = Uri.fromFile(file)
                        imageView.load(photoUri) {
                            crossfade(true)
                            transformations(CircleCropTransformation())
                        }
                        performAnalysisAndSetFlag()
                    }
                } else {
                    Toast.makeText(this, "Фотосъемка отменена.", Toast.LENGTH_SHORT).show()
                }
            }

        selectPictureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val selectedImageUri: Uri? = result.data?.data
                    if (selectedImageUri != null) {
                        photoUri = selectedImageUri
                        imageView.load(selectedImageUri) {
                            crossfade(true)
                            transformations(CircleCropTransformation())
                        }

                        performAnalysisAndSetFlag()
                    }
                }
            }

        uploadButton.setOnClickListener {
            showImagePickerDialog()
        }

    }


    private fun showImagePickerDialog() {
        val items = arrayOf("Сделать снимок", "Выбрать из библиотеки", "Отмена")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Добавьте фото!")
        builder.setItems(items) { dialog, item ->
            when (items[item]) {
                "Сделать снимок" -> checkCameraPermission()
                "Выбрать из библиотеки" -> checkStoragePermission()
                "Отмена" -> dialog.dismiss()
            }
        }
        builder.show()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("MainActivity", "Разрешение на использование камеры уже предоставлено")
            dispatchTakePictureIntent()
        } else {
            Log.i("MainActivity", "Запрашивается разрешение камеры")
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun dispatchTakePictureIntent() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            val photoFile: File? = try {
                createImageFile()
            } catch (ex: IOException) {
                Log.e("MainActivity", "Ошибка при создании файла изображения: ${ex.message}")
                Toast.makeText(this, "Ошибка при создании файла изображения.", Toast.LENGTH_SHORT).show()
                null
            }

            photoFile?.also {
                photoUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    it
                )
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                takePictureLauncher.launch(takePictureIntent)
            }
        } else {
            Toast.makeText(this, "Приложение для камеры не найдено.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("MainActivity", "Разрешение на хранение уже предоставлено")
            selectImageFromGallery()
        } else {
            Log.i("MainActivity", "Запрашивается разрешение на хранение")
            requestStoragePermissionLauncher.launch(permission)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        Log.d("StorageDir", "Storage directory: ${storageDir?.absolutePath}")

        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        ).apply {
            currentPhotoPath = absolutePath
        }
    }

    private fun selectImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        selectPictureLauncher.launch(intent)
    }


    private fun showRandomFrameImage() {
        val frameImages = listOf(ovalFrames, roundFrames, squareFrames)
        val randomFrame = frameImages.random()
        recommendedImageView.setImageResource(randomFrame)
    }

    private fun performAnalysisAndSetFlag() {
        showRandomFrameImage()
        analysisPerformed = true
    }

}
