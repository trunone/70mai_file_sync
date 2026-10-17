package com.lz1bgb.camhttp

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.platformstorage.PlatformStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private class AppDocumentBuilder(namespace: String, id: String, schemaType: String) :
    GenericDocument.Builder<AppDocumentBuilder>(namespace, id, schemaType)

object AppSearchInitializer {
    private const val TAG = "AppSearchInitializer"

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d(TAG, "AppSearch PlatformStorage is only supported on API level 31+")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                initAppSearchPlatformStorage(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing AppSearch: ${e.message}", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun initAppSearchPlatformStorage(context: Context) {
        val contextRef = context.applicationContext
        val packageName = contextRef.packageName

        val searchContext = PlatformStorage.SearchContext.Builder(contextRef, "apps-db").build()
        val sessionFuture = PlatformStorage.createSearchSessionAsync(searchContext)
        val session = sessionFuture.get()

        val schemaTypePerPackage = "builtin:MobileApplication-$packageName"
        val schemaTypeGeneric = "builtin:MobileApplication"

        val mobileAppSchemaPerPackage = AppSearchSchema.Builder(schemaTypePerPackage)
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder("name")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()
            )
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder("className")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()
            )
            .build()

        val mobileAppSchemaGeneric = AppSearchSchema.Builder(schemaTypeGeneric)
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder("name")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()
            )
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder("className")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()
            )
            .build()

        val setSchemaRequest = SetSchemaRequest.Builder()
            .addSchemas(mobileAppSchemaPerPackage, mobileAppSchemaGeneric)
            .setSchemaTypeDisplayedBySystem(schemaTypePerPackage, true)
            .setSchemaTypeDisplayedBySystem(schemaTypeGeneric, true)
            .setForceOverride(true)
            .build()

        session.setSchemaAsync(setSchemaRequest).get()

        val documentPerPackage = AppDocumentBuilder("apps", packageName, schemaTypePerPackage)
            .setPropertyString("name", contextRef.getString(R.string.app_name))
            .setPropertyString("className", SettingsActivity::class.java.name)
            .build()

        val documentGeneric = AppDocumentBuilder("apps", "${packageName}_generic", schemaTypeGeneric)
            .setPropertyString("name", contextRef.getString(R.string.app_name))
            .setPropertyString("className", SettingsActivity::class.java.name)
            .build()

        val putRequest = PutDocumentsRequest.Builder()
            .addGenericDocuments(documentPerPackage, documentGeneric)
            .build()

        session.putAsync(putRequest).get()
        Log.i(TAG, "AppSearch schema and document successfully registered for $packageName")
    }
}
