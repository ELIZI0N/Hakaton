package com.example.hakaton


import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var uploadButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var resultTextView: TextView
    private lateinit var recommendedImageView: ImageView
    private var photoUri: Uri? = null
    private lateinit var takePictureLauncher: ActivityResultLauncher<Intent>
    private lateinit var selectPictureLauncher: ActivityResultLauncher<Intent>
    private lateinit var currentPhotoPath: String
    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        uploadButton = findViewById(R.id.uploadButton)
        analyzeButton = findViewById(R.id.analyzeButton)
        resultTextView = findViewById(R.id.resultTextView)
        recommendedImageView = findViewById(R.id.recommendedImageView)

        takePictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Image captured successfully, set it to the image view
                val file = File(currentPhotoPath)
                photoUri = Uri.fromFile(file)
                imageView.load(photoUri)
            } else {
                // Image capture failed, handle accordingly
                Toast.makeText(this, "Picture taking cancelled.", Toast.LENGTH_SHORT).show()
            }
        }

        selectPictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val selectedImageUri: Uri? = result.data?.data
                if (selectedImageUri != null) {
                    photoUri = selectedImageUri
                    imageView.load(selectedImageUri)
                }
            }
        }

        uploadButton.setOnClickListener {
            showImagePickerDialog()
        }

        analyzeButton.setOnClickListener {
            photoUri?.let { uri ->
                analyzeImage(uri)
            } ?: run {
                Toast.makeText(this, "Please upload a photo first.", Toast.LENGTH_SHORT).show()
            }
        }

        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
        }

        // Request storage permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
                Toast.makeText(this, "Permissions granted.", Toast.LENGTH_SHORT).show()
            } else {
                // Permission denied
                Toast.makeText(this, "Permissions denied. Camera access is required.", Toast.LENGTH_SHORT).show()
                finish() // Close the activity if permissions are crucial
            }
        }
    }

    private fun showImagePickerDialog() {
        val items = arrayOf("Take Photo", "Choose from Library", "Cancel")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add Photo!")
        builder.setItems(items) { dialog, item ->
            when (items[item]) {
                "Take Photo" -> dispatchTakePictureIntent()
                "Choose from Library" -> selectImageFromGallery()
                "Cancel" -> dialog.dismiss()
            }
        }
        builder.show()
    }

    // Take photo using camera
    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            // Ensure that there's a camera activity to handle the intent
            takePictureIntent.resolveActivity(packageManager)?.also {
                // Create the File where the photo should go
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {
                    // Error occurred while creating the File
                    null
                }
                // Continue only if the File was successfully created
                photoFile?.also {
                    photoUri = FileProvider.getUriForFile(
                        this,
                        "com.example.myapplication.fileprovider",
                        it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    takePictureLauncher.launch(takePictureIntent)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
        }
    }

    // Select image from gallery
    private fun selectImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        selectPictureLauncher.launch(intent)
    }

    private fun analyzeImage(imageUri: Uri) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val bitmap = uriToBitmap(imageUri) ?: throw IOException("Failed to convert Uri to Bitmap")
                val response = uploadImage(bitmap)
                Log.d("Response", response)
                // Parse JSON response and extract the frame type
                val jsonResponse = JSONObject(response)
                val frameType = jsonResponse.getString("frame_type") // Adjust key based on your API response
                // Update UI with the result
                withContext(Dispatchers.Main) {
                    resultTextView.text = "Recommended Frame Shape: $frameType"
                    // Load the corresponding image based on the frameType
                    val imageResource = when (frameType) {
                        "round" -> R.drawable.round_frames
                        "square" -> R.drawable.square_frames
                        "oval" -> R.drawable.oval_frames
                        else -> R.drawable.ic_launcher_background // Default image if type not recognized
                    }
                    recommendedImageView.setImageResource(imageResource)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Convert Uri to Bitmap
    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    // Upload image to server and get the predicted glasses frame
    private suspend fun uploadImage(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val byteArray = stream.toByteArray()

            val requestBody: RequestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "image.jpg",
                    RequestBody.create("image/jpeg".toMediaTypeOrNull(), byteArray))
                .build()

            val request: Request = Request.Builder()
                .url("YOUR_API_ENDPOINT_HERE") // Replace with your actual API endpoint
                .post(requestBody)
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS) // Increase timeout if needed
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IOException("Failed to upload image: ${response.code}")
                }
                response.body?.string() ?: ""
            } catch (e: IOException) {
                throw e
            }
        }
    }
}
