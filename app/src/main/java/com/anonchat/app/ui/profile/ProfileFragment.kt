package com.anonchat.app.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.anonchat.app.databinding.FragmentProfileBinding
import com.anonchat.app.ui.auth.AuthActivity
import com.anonchat.app.data.repository.AuthRepository
import com.anonchat.app.data.repository.UserRepository
import com.anonchat.app.ui.secretcode.SecretCodeSetupActivity
import com.anonchat.app.util.AppHider
import com.anonchat.app.util.SecretCodeManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ProfileViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val factory = ProfileViewModelFactory(
            UserRepository(FirebaseFirestore.getInstance()),
            AuthRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()),
            userId
        )
        viewModel = ViewModelProvider(this, factory)[ProfileViewModel::class.java]

        observeProfile()
        setupClickListeners()
        updateHideAppSection()
    }

    private fun observeProfile() {
        viewModel.user.observe(viewLifecycleOwner) { user ->
            user?.let {
                binding.tvUsername.text = "@${it.username}"
                binding.tvBio.text = it.bio
                binding.etBio.setText(it.bio)

                try {
                    binding.tvAvatarInitials.setBackgroundColor(android.graphics.Color.parseColor(it.avatarColor))
                } catch (_: Exception) { }

                binding.tvAvatarInitials.text = it.getInitials()

                if (it.isOnline) {
                    binding.tvStatus.text = "Online"
                } else {
                    binding.tvStatus.text = "Last seen ${com.anonchat.app.util.TimestampConverter.toRelativeTime(it.lastSeen)}"
                }
            }
        }

        viewModel.updateState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ProfileViewModel.UpdateState.Success -> {
                    Toast.makeText(requireContext(), "Updated!", Toast.LENGTH_SHORT).show()
                    binding.btnSaveBio.visibility = View.GONE
                }
                is ProfileViewModel.UpdateState.Error -> {
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
    }

    private fun updateHideAppSection() {
        val codeSet = SecretCodeManager.isCodeSet(requireContext())

        if (codeSet) {
            val code = SecretCodeManager.getSecretCode(requireContext())
            binding.tvHideAppStatus.text = "Secret code: ${code} (Type in Calculator)"
            binding.btnSetupSecretCode.text = "Change Secret Code"
        } else {
            binding.tvHideAppStatus.text = "No secret code set"
            binding.btnSetupSecretCode.text = "Set Secret Code"
        }

        binding.tvAppVisibility.text = "App is protected by Calculator disguise"
        binding.tvAppVisibility.setTextColor(
            android.graphics.Color.parseColor("#4CAF50")
        )
        binding.btnToggleAppVisibility.text = "Lock to Calculator"
        binding.btnToggleAppVisibility.setBackgroundColor(
            android.graphics.Color.parseColor("#3949AB")
        )
    }

    private fun updateFaceLockSection() {
        val context = context ?: return
        val isEnabled = com.anonchat.app.util.FaceGuardManager.isFaceLockEnabled(context)
        val isEnrolled = com.anonchat.app.util.FaceGuardManager.isFaceEnrolled(context)

        binding.switchFaceLock.isChecked = isEnabled

        if (!isEnrolled) {
            binding.tvFaceLockStatus.text = "Status: Not Registered (Register face to enable)"
            binding.tvFaceLockStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))
        } else if (isEnabled) {
            binding.tvFaceLockStatus.text = "Status: Active (1.5s Low-Power Sampling)"
            binding.tvFaceLockStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            binding.tvFaceLockStatus.text = "Status: Disabled"
            binding.tvFaceLockStatus.setTextColor(android.graphics.Color.parseColor("#B3FFFFFF"))
        }
    }

    private fun setupClickListeners() {
        binding.etBio.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.btnSaveBio.visibility = View.VISIBLE
            }
        }

        binding.btnSaveBio.setOnClickListener {
            val bio = binding.etBio.text.toString().trim()
            viewModel.updateBio(bio)
        }

        binding.btnSetupSecretCode.setOnClickListener {
            startActivity(Intent(requireContext(), SecretCodeSetupActivity::class.java))
        }

        binding.btnToggleAppVisibility.setOnClickListener {
            AppHider.hideApp(requireContext())
        }

        binding.switchFaceLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !com.anonchat.app.util.FaceGuardManager.isFaceEnrolled(requireContext())) {
                Toast.makeText(requireContext(), "Please register your face first!", Toast.LENGTH_SHORT).show()
                binding.switchFaceLock.isChecked = false
                return@setOnCheckedChangeListener
            }
            com.anonchat.app.util.FaceGuardManager.setFaceLockEnabled(requireContext(), isChecked)
            updateFaceLockSection()
        }

        binding.btnRegisterFace.setOnClickListener {
            registerFacePrompt()
        }

        binding.btnLogout.setOnClickListener {
            viewModel.signOut()
            val disguiseIntent = com.anonchat.app.util.AppHider.getDisguiseIntent(requireContext())
            startActivity(disguiseIntent)
            requireActivity().finish()
        }
    }

    private fun registerFacePrompt() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
            return
        }

        Toast.makeText(requireContext(), "Look directly at front camera to register face...", Toast.LENGTH_SHORT).show()

        val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA

                val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(androidx.core.content.ContextCompat.getMainExecutor(requireContext())) { imageProxy ->
                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val inputImage = com.google.mlkit.vision.common.InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        com.anonchat.app.util.FaceGuardManager.enrollFromInputImage(requireContext(), inputImage) { success, message ->
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                            if (success) {
                                cameraProvider.unbindAll()
                                updateFaceLockSection()
                            }
                            imageProxy.close()
                        }
                    } else {
                        imageProxy.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        updateHideAppSection()
        updateFaceLockSection()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
