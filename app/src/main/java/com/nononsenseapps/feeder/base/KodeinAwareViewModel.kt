package com.nononsenseapps.feeder.base

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.Fragment
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.SavedStateRegistryOwner
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bind
import org.kodein.di.compose.LocalDI
import org.kodein.di.compose.instance
import org.kodein.di.direct
import org.kodein.di.factory
import org.kodein.di.instance
import org.kodein.di.provider
import java.lang.reflect.InvocationTargetException

/**
 * A view model which is also kodein aware. Construct any deriving class by using the getViewModel()
 * extension function.
 */
abstract class DIAwareViewModel(override val di: DI) :
    AndroidViewModel(di.direct.instance()), DIAware

class DIAwareViewModelFactory(
    override val di: DI
) : ViewModelProvider.AndroidViewModelFactory(di.direct.instance()), DIAware {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return if (DIAwareViewModel::class.java.isAssignableFrom(modelClass)) {
            try {
                modelClass.getConstructor(DI::class.java).newInstance(di)
            } catch (e: NoSuchMethodException) {
                throw RuntimeException("No such constructor $modelClass", e)
            } catch (e: IllegalAccessException) {
                throw RuntimeException("Cannot create an instance of $modelClass", e)
            } catch (e: InstantiationException) {
                throw RuntimeException("Cannot create an instance of $modelClass", e)
            } catch (e: InvocationTargetException) {
                throw RuntimeException("Cannot create an instance of $modelClass", e)
            }
        } else {
            super.create(modelClass)
        }
    }
}

inline fun <reified T : DIAwareViewModel> DI.Builder.bindWithDIAwareViewModelFactory() {
    bind<T>() with activityViewModelProvider()
    bind<T>() with factory { fragment: Fragment ->
        ViewModelProvider(fragment, instance<DIAwareViewModelFactory>()).get(T::class.java)
    }
}

inline fun <reified T : DIAwareViewModel> DI.Builder.activityViewModelProvider() = provider {
    ViewModelProvider(
        instance<ComponentActivity>(),
        instance<DIAwareViewModelFactory>()
    ).get(T::class.java)
}

class DIAwareSavedStateViewModelFactory(
    override val di: DI,
    owner: SavedStateRegistryOwner,
    defaultArgs: Bundle? = null
) : AbstractSavedStateViewModelFactory(owner, defaultArgs), DIAware {
    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        return if (DIAwareViewModel::class.java.isAssignableFrom(modelClass)) {
            try {
                modelClass.getConstructor(DI::class.java, SavedStateHandle::class.java).newInstance(di, handle)
            } catch (orgError: Exception) {
                try {
                    modelClass.getConstructor(DI::class.java).newInstance(di)
                } catch (e: Exception) {
                    throw orgError
                }
            }
        } else {
            throw RuntimeException("Not a DIAwareViewModel! Please use by viewModel() instead")
        }
    }
}

@Composable
inline fun <reified T : DIAwareViewModel> SavedStateRegistryOwner.DIAwareViewModel(
    key: String? = null
): T {
    val factory = DIAwareSavedStateViewModelFactory(LocalDI.current, this)

    return viewModel(T::class.java, key, factory)
}
