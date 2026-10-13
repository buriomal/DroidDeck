/* droiddeck-vkprobe: lists the Vulkan devices the session's loader finds and tries to open each,
 * so a session log says whether a driver sees the GPU before anything else depends on it. */
#include <stdio.h>
#include <string.h>
#define VK_NO_PROTOTYPES
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#define FN(name) static PFN_##name name
FN(vkCreateInstance); FN(vkEnumeratePhysicalDevices); FN(vkGetPhysicalDeviceProperties2);
FN(vkGetPhysicalDeviceFeatures); FN(vkEnumerateDeviceExtensionProperties); FN(vkCreateDevice);
FN(vkDestroyDevice); FN(vkDestroyInstance);

int main(void)
{
   void *lib = dlopen("libvulkan.so.1", RTLD_NOW);
   if (!lib) {
      printf("no Vulkan loader: %s\n", dlerror());
      return 1;
   }
   PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
   vkCreateInstance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
   VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "droiddeck-vkprobe", .apiVersion = VK_API_VERSION_1_3 };
   VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
   VkInstance inst;
   VkResult r = vkCreateInstance(&ici, NULL, &inst);
   if (r != VK_SUCCESS) {
      printf("vkCreateInstance failed: %d\n", r);
      return 1;
   }
#define LOAD(name) name = (PFN_##name)gipa(inst, #name)
   LOAD(vkEnumeratePhysicalDevices); LOAD(vkGetPhysicalDeviceProperties2); LOAD(vkGetPhysicalDeviceFeatures);
   LOAD(vkEnumerateDeviceExtensionProperties); LOAD(vkCreateDevice); LOAD(vkDestroyDevice); LOAD(vkDestroyInstance);
   uint32_t n = 0;
   r = vkEnumeratePhysicalDevices(inst, &n, NULL);
   printf("physical devices: %u (result %d)\n", n, r);
   VkPhysicalDevice devs[8];
   if (n > 8) n = 8;
   vkEnumeratePhysicalDevices(inst, &n, devs);
   for (uint32_t i = 0; i < n; i++) {
      VkPhysicalDeviceDriverProperties drv = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
      VkPhysicalDeviceProperties2 p = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &drv };
      vkGetPhysicalDeviceProperties2(devs[i], &p);
      printf("device %u: %s, Vulkan %u.%u.%u, driver %s (%s), vendor 0x%x device 0x%x\n", i,
             p.properties.deviceName, VK_API_VERSION_MAJOR(p.properties.apiVersion),
             VK_API_VERSION_MINOR(p.properties.apiVersion), VK_API_VERSION_PATCH(p.properties.apiVersion),
             drv.driverName, drv.driverInfo, p.properties.vendorID, p.properties.deviceID);
      VkPhysicalDeviceFeatures f;
      vkGetPhysicalDeviceFeatures(devs[i], &f);
      printf("  geometryShader=%u tessellationShader=%u textureCompressionBC=%u shaderClipDistance=%u depthClamp=%u\n",
             f.geometryShader, f.tessellationShader, f.textureCompressionBC, f.shaderClipDistance, f.depthClamp);
      uint32_t ne = 0;
      vkEnumerateDeviceExtensionProperties(devs[i], NULL, &ne, NULL);
      printf("  device extensions: %u\n", ne);
      float prio = 1.0f;
      VkDeviceQueueCreateInfo q = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                    .queueFamilyIndex = 0, .queueCount = 1, .pQueuePriorities = &prio };
      VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                                 .queueCreateInfoCount = 1, .pQueueCreateInfos = &q };
      VkDevice dev;
      r = vkCreateDevice(devs[i], &dci, NULL, &dev);
      printf("  vkCreateDevice: %s (%d)\n", r == VK_SUCCESS ? "ok" : "FAILED", r);
      if (r == VK_SUCCESS) {
         PFN_vkDestroyDevice destroy = (PFN_vkDestroyDevice)gipa(inst, "vkDestroyDevice");
         destroy(dev, NULL);
      }
   }
   vkDestroyInstance(inst, NULL);
   return n ? 0 : 2;
}
