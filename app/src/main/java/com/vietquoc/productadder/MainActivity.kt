package com.vietquoc.productadder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.storage
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import com.vietquoc.productadder.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val productStorage = Firebase.storage.reference
    private val firestore = Firebase.firestore

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private var selectedImages = mutableListOf<Uri>()
    private var selectedColors = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.buttonColorPicker.setOnClickListener {
            ColorPickerDialog.Builder(this)
                .setTitle("Product color")
                .setPositiveButton("Select", object : ColorEnvelopeListener {
                    override fun onColorSelected(p0: ColorEnvelope?, p1: Boolean) {
                        p0?.let {
                            selectedColors.add(it.color)
                            updateColor()
                        }
                    }

                }).setNegativeButton("Cancel") { dialogInterface, i ->
                    dialogInterface.dismiss()
                }.show()
        }

        val selectedImagesActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val intent = result.data
                    intent?.let {
                        val clipData = it.clipData
                        if (clipData != null) {
                            // Multiple images selected
                            for (i in 0 until clipData.itemCount) {
                                val imageUri = clipData.getItemAt(i).uri
                                selectedImages.add(imageUri)
                            }
                        } else {
                            // Single image selected
                            it.data?.let { uri -> selectedImages.add(uri) }
                        }
                    }
                    updateImage()
                }
            }

        binding.buttonImagesPicker.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                type = "image/*"
            }
            selectedImagesActivityResult.launch(intent)
        }

    }

    private fun updateImage() {
        binding.tvSelectedImages.text = selectedImages.size.toString()
    }


    private fun updateColor() {
        var color = ""
        selectedColors.forEach {
            color = "$color ${Integer.toHexString(it)}"
        }
        binding.tvSelectedColors.text = color
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.saveProduct) {
            val productValidation = validateInformation()
            if (!productValidation) {
                Toast.makeText(this, "Check your input", Toast.LENGTH_LONG).show()
                return false
            }

            saveProduct()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun saveProduct() {
        val name = binding.edName.text.toString().trim()
        val price = binding.edPrice.text.toString().trim()
        val category = binding.edCategory.text.toString().trim()
        val offerPercentage = binding.offerPercentage.text.toString().trim()
        val description = binding.edDescription.text.toString().trim()
        val sizes = getSizesList(binding.edSizes.text.toString().trim())
        val imageByteArrays = getImageByteArrays()
        val images = mutableListOf<String>()

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                showLoading()
            }

            try {
                val uploadTasks = imageByteArrays.map { byteArray ->
                    async {
                        val id = UUID.randomUUID().toString()
                        val imageStorage = productStorage.child("products/images/$id")
                        val result = imageStorage.putBytes(byteArray).await()
                        result.storage.downloadUrl.await().toString()
                    }
                }
                images.addAll(uploadTasks.awaitAll()) // await for all uploads to finish and extract the urls


                val product = Product(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    category = category,
                    price = price.toFloat(),
                    offerPercentage = if (offerPercentage.isEmpty()) null else offerPercentage.toFloat(),
                    description = description,
                    colors = if (selectedColors.isEmpty()) null else selectedColors,
                    sizes = sizes,
                    images = images,
                )

                firestore.collection("Products").add(product)
                    .addOnSuccessListener {
                        hideLoading()
                    }
                    .addOnFailureListener { e ->
                        hideLoading()
                        Log.e("Error", e.message.toString())
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to save product: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    hideLoading()
                    Toast.makeText(
                        this@MainActivity,
                        "Error during upload: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun hideLoading() {
        binding.progressbar.visibility = View.INVISIBLE
    }

    private fun showLoading() {
        binding.progressbar.visibility = View.VISIBLE
    }

    private fun getImageByteArrays(): List<ByteArray> {
        val imageByteArray = mutableListOf<ByteArray>()
        selectedImages.forEach {
            val stream = contentResolver.openInputStream(it)
            val data = stream?.readBytes()
            data?.let { imageByteArray.add(it) }
        }
        return imageByteArray
    }

    private fun getSizesList(sizeStr: String): List<String> {
        if (sizeStr.isEmpty()) return emptyList()
        val sizes = sizeStr.split(",")
        return sizes.map { it.trim() }
    }

    private fun validateInformation(): Boolean {
        if (binding.edName.text.toString().trim().isEmpty() ||
            binding.edPrice.text.toString().trim().isEmpty() ||
            binding.edCategory.text.toString().trim().isEmpty()
        ) {
            return false
        }

        if (selectedImages.isEmpty()) return false

        return true
    }
}