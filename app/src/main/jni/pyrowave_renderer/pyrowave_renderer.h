#pragma once

// MoonCast's PyroWave renderer: decodes PyroWave frames on Vulkan (PyrowaveDecoder, from Moonlight
// X) and draws each one straight to the stream's surface through a Vulkan swapchain. The device,
// swapchain and draw setup are adapted from Moonlight X's Vulkan renderer
// (https://github.com/MoreOrLessSoftware/moonlight-android, GPL-3.0,
// app/src/main/jni/vulkan/vulkan_renderer.cpp at 3e6665e7): only the parts PyroWave needs to reach
// the screen. Frames are shown as they're decoded (mailbox where the surface has it), with none of
// Moonlight X's frame pacing, jitter buffer, front buffer or HDR output.

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <android/native_window.h>

#include "pyrowave_decoder.h"
#include "vk_api.h"

namespace vkr {

struct PyrowaveStats {
    int64_t decodeNs = 0;     // Decoding the frames below took, CPU and GPU, without the waits
    uint32_t decoded = 0;     // Frames decoded (whose decode time is above)
    uint32_t presented = 0;   // Frames handed to the screen
    uint32_t dropped = 0;     // Frames that couldn't be decoded or shown
};

class PyrowaveRenderer {
public:
    // Whether PyroWave can decode on this device: the library loads, and a decoder can be made on a
    // device set up as create() would. Tried once per process.
    static bool probe();

    // colorspace: MoonBridge.COLORSPACE_* the host encodes in. centerChroma: the host sites 4:2:0
    // chroma at the center of each 2x2 quad (hosts that send x-ss-pyrowave.bitstream), rather than
    // with the left luma sample of each pair. Null if anything fails.
    static std::unique_ptr<PyrowaveRenderer> create(ANativeWindow* window, int width, int height, int colorspace,
                                                    bool centerChroma);

    ~PyrowaveRenderer();

    PyrowaveRenderer(const PyrowaveRenderer&) = delete;
    PyrowaveRenderer& operator=(const PyrowaveRenderer&) = delete;

    // Decodes a whole frame and shows it. Frames come from one thread. False if it was dropped.
    bool submit(const uint8_t* data, size_t size);

    // The counts since the last call. Thread safe.
    PyrowaveStats takeStats();

    // "Adreno (TM) 740, fragment path", for the stats
    std::string describe() const { return description_; }

private:
    struct InstanceSetup {
        VkApplicationInfo app {VK_STRUCTURE_TYPE_APPLICATION_INFO};
        VkInstanceCreateInfo info {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        std::vector<const char*> extensions;
        uint32_t apiVersion = 0;
    };
    struct DeviceSetup {
        VkDeviceQueueCreateInfo queue {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        float priority = 1.0f;
        VkDeviceCreateInfo info {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        std::vector<const char*> extensions;
        PyrowaveDeviceFeatures pyrowave;
    };

    static VkInstance createInstance(VkApi& vk, InstanceSetup& setup);
    static bool prepareDevice(VkApi& vk, VkPhysicalDevice device, uint32_t queueFamily, uint32_t instanceApi,
                              DeviceSetup& setup);

    PyrowaveRenderer() = default;
    bool init(ANativeWindow* window, int width, int height, int colorspace, bool centerChroma);
    bool pickDevice();
    bool createDevice();
    bool createFrameResources();
    bool ensureSwapchain();
    bool createSwapchain();
    void destroySwapchain();
    bool createPipeline();
    // Waits for the render in a slot and lets go of its planes, counting its decode
    bool retireSlot(int slot);
    void releasePlanes(PyrowavePlanes* planes);

    VkApi vk_;
    InstanceSetup instanceSetup_;
    DeviceSetup deviceSetup_;
    ANativeWindow* window_ = nullptr;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    uint32_t timestampValidBits_ = 0;
    float timestampPeriodNs_ = 0.0f;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    // Every submission to the queue, PyroWave's too
    std::mutex queueMutex_;

    std::unique_ptr<PyrowaveDecoder> decoder_;
    int width_ = 0;
    int height_ = 0;
    int colorspace_ = 1;
    bool centerChroma_ = false;

    VkShaderModule vertShader_ = VK_NULL_HANDLE;
    VkShaderModule fragShader_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFormat renderPassFormat_ = VK_FORMAT_UNDEFINED;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    bool swapchainDirty_ = true;
    VkExtent2D extent_ {};
    VkSurfaceTransformFlagBitsKHR preTransform_ = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    std::vector<VkImageView> swapchainViews_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkSemaphore> renderDone_;

    static constexpr int kFramesInFlight = 2;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffers_[kFramesInFlight] {};
    VkFence fences_[kFramesInFlight] {};
    VkSemaphore imageAcquired_[kFramesInFlight] {};
    // The planes each slot's render samples, and the decode that filled them
    PyrowavePlanes* slotPlanes_[kFramesInFlight] {};
    uint64_t slotReady_[kFramesInFlight] {};
    uint64_t frameCounter_ = 0;
    // When the last decode counted ended on the GPU: one can start before the one before ends,
    // so its run is counted from then
    int64_t lastGpuEndNs_ = 0;

    std::mutex statsMutex_;
    PyrowaveStats stats_;

    std::string description_;
};

}  // namespace vkr
