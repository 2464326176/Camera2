
public class Camera2Helper {
    

    /**
     * 拍照处理接口 - 支持单帧和多帧算法扩展
     */
    public interface ImageProcessCallback {
        void onImageProcessed(Image image, CaptureResult result);
        void onMultiFrameProcessed(List<Image> images, List<CaptureResult> results);
        void onError(Exception e);
    }

    private ImageProcessCallback mImageProcessCallback;
    private List<Image> mMultiFrameBuffer = new ArrayList<>();
    private List<CaptureResult> mMultiFrameResults = new ArrayList<>();
    private int mMultiFrameCount = 1; // 默认单帧处理

    /**
     * 设置图像处理回调接口
     * @param callback 处理回调
     */
    public void setImageProcessCallback(ImageProcessCallback callback) {
        this.mImageProcessCallback = callback;
    }

    /**
     * 设置多帧处理数量
     * @param frameCount 帧数
     */
    public void setMultiFrameCount(int frameCount) {
        this.mMultiFrameCount = frameCount;
    }

    /**
     * 图像处理方法 - 直接使用buffer优化性能
     * @param image 图像数据buffer
     * @param result 拍照结果
     */
    private void processImageBuffer(Image image, CaptureResult result) {
        if (mImageProcessCallback == null) {
            return;
        }

        try {
            // 单帧处理模式
            if (mMultiFrameCount <= 1) {
                mImageProcessCallback.onImageProcessed(image, result);
            } 
            // 多帧处理模式
            else {
                mMultiFrameBuffer.add(image);
                mMultiFrameResults.add(result);
                
                // 当收集到足够的帧数时进行处理
                if (mMultiFrameBuffer.size() >= mMultiFrameCount) {
                    mImageProcessCallback.onMultiFrameProcessed(
                        new ArrayList<>(mMultiFrameBuffer), 
                        new ArrayList<>(mMultiFrameResults)
                    );
                    // 清空缓冲区
                    mMultiFrameBuffer.clear();
                    mMultiFrameResults.clear();
                }
            }
        } catch (Exception e) {
            mImageProcessCallback.onError(e);
        }
    }

    /**
     * 图像可用时的回调处理
     */
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            // 直接使用传入的buffer，避免读取照片文件
            Image image = reader.acquireLatestImage();
            if (image != null) {
                try {
                    // 获取对应的拍照结果
                    CaptureResult result = mCaptureResult;
                    if (result != null) {
                        // 直接处理buffer数据，不读取照片文件
                        processImageBuffer(image, result);
                    }
                } finally {
                    // 注意：必须close image以避免内存泄漏
                    image.close();
                }
            }
        }

    };


    /**
     * 单帧算法处理实现示例
     */
    public static class SingleFrameProcessor {
        public void process(Image image, CaptureResult result) {
            // 实现单帧算法处理逻辑
            // 直接操作image buffer数据
        }
    }

    /**
     * 多帧算法处理实现示例
     */
    public static class MultiFrameProcessor {
        public void process(List<Image> images, List<CaptureResult> results) {
            // 实现多帧算法处理逻辑
            // 可以进行降噪、HDR等处理
        }
    }

}
