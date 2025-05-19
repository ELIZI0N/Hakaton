
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

    // Use a single permission request code
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private val STORAGE_PERMISSION_REQUEST_CODE = 101

    // Use ActivityResultLauncher for camera permission
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("MainActivity", "Camera permission granted")
                dispatchTakePictureIntent() // Launch camera if permission granted
            } else {
                Log.i("MainActivity", "Camera permission denied")
                Toast.makeText(
                    this,
                    "Camera permission is required to take photos.",
                    Toast.LENGTH_SHORT
                ).show()
                // Optionally disable the "Take Photo" option if permission denied
            }
        }

    // Use ActivityResultLauncher for storage permission if needed (API < 33)
    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("MainActivity", "Storage permission granted")
                selectImageFromGallery() // Launch gallery if permission granted
            } else {
                Log.i("MainActivity", "Storage permission denied")
                Toast.makeText(
                    this,
                    "Storage permission is required to access gallery.",
                    Toast.LENGTH_SHORT
                ).show()
                // Optionally disable the "Choose from Library" option if permission denied
            }
        }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        uploadButton = findViewById(R.id.uploadButton)
        analyzeButton = findViewById(R.id.analyzeButton)
        resultTextView = findViewById(R.id.resultTextView)
        recommendedImageView = findViewById(R.id.recommendedImageView)

        takePictureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    // Image captured successfully, set it to the image view
                    val file = File(currentPhotoPath)
                    photoUri = Uri.fromFile(file)
                    imageView.load(photoUri)
                } else {
                    // Image capture failed, handle accordingly
                    Toast.makeText(this, "Picture taking cancelled.", Toast.LENGTH_SHORT).show()
                }
            }

        selectPictureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
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


        // Initial check for permissions in onCreate is generally NOT needed,
        // because showImagePickerDialog requests the permissions only when needed.
        // This reduces unnecessary permission prompts.
    }


    private fun showImagePickerDialog() {
        val items = arrayOf("Take Photo", "Choose from Library", "Cancel")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add Photo!")
        builder.setItems(items) { dialog, item ->
            when (items[item]) {
                "Take Photo" -> checkCameraPermission() // Check camera permission before launching
                "Choose from Library" -> checkStoragePermission() // Check storage permission
                "Cancel" -> dialog.dismiss()
            }
        }
        builder.show()
    }

    // Check camera permission
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission already granted
            Log.i("MainActivity", "Camera permission already granted")
            dispatchTakePictureIntent()
        } else {
            // Request permission
            Log.i("MainActivity", "Requesting camera permission")
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Take photo using camera
    private fun dispatchTakePictureIntent() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            // Create the File where the photo should go
            val photoFile: File? = try {
                createImageFile()
            } catch (ex: IOException) {
                // Error occurred while creating the File
                Log.e("MainActivity", "Error creating image file: ${ex.message}")
                null
            }

            // Continue only if the File was successfully created
// In your MainActivity.kt, inside the dispatchTakePictureIntent() function:
            photoFile?.also {
                photoUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    it // Use the File object directly
                )
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                takePictureLauncher.launch(takePictureIntent)
            }
        } else {
            Toast.makeText(this, "No camera app found.", Toast.LENGTH_SHORT).show()
        }
    }


    //Check storage permission
    private fun checkStoragePermission() {
        // For API level 33 and above, READ_EXTERNAL_STORAGE is deprecated, use READ_MEDIA_IMAGES instead
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission already granted
            Log.i("MainActivity", "Storage permission already granted")
            selectImageFromGallery()
        } else {
            // Request permission
            Log.i("MainActivity", "Requesting storage permission")
            requestStoragePermissionLauncher.launch(permission)
        }
    }


    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        Log.d("StorageDir", "Storage directory: ${storageDir?.absolutePath}") // ADD THIS LINE

        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir      /* directory */
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
                    // Load a random image from the corresponding set based on the frameType
                    val imageResource = getRandomImageResource(frameType)
                    recommendedImageView.setImageResource(imageResource)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Function to get a random image resource from any of the frame types
    private fun getRandomImageResource(): Int {
        val allFrames = listOf(
            R.drawable.oval_frames, R.drawable.round_frames, R.drawable.square_frames
        )
        return allFrames.random()
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