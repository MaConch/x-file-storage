package org.dromara.x.file.storage.core.platform;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.text.NamingCase;
import cn.hutool.core.util.CharUtil;
import cn.hutool.core.util.StrUtil;
import com.baidubce.BceServiceException;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.model.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageProperties.BaiduBosConfig;
import org.dromara.x.file.storage.core.ProgressListener;
import org.dromara.x.file.storage.core.UploadPretreatment;
import org.dromara.x.file.storage.core.exception.FileStorageRuntimeException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 百度云 BOS 存储
 */
@Getter
@Setter
@NoArgsConstructor
public class BaiduBosFileStorage implements FileStorage {
    private String platform;
    private String bucketName;
    private String domain;
    private String basePath;
    private String defaultAcl;
    private int multipartThreshold;
    private int multipartPartSize;
    private FileStorageClientFactory<BosClient> clientFactory;

    public BaiduBosFileStorage(BaiduBosConfig config,FileStorageClientFactory<BosClient> clientFactory) {
        platform = config.getPlatform();
        bucketName = config.getBucketName();
        domain = config.getDomain();
        basePath = config.getBasePath();
        defaultAcl = config.getDefaultAcl();
        multipartThreshold = config.getMultipartThreshold();
        multipartPartSize = config.getMultipartPartSize();
        this.clientFactory = clientFactory;
    }

    public BosClient getClient() {
        return clientFactory.getClient();
    }


    @Override
    public void close() {
        clientFactory.close();
    }

    public String getFileKey(FileInfo fileInfo) {
        return fileInfo.getBasePath() + fileInfo.getPath() + fileInfo.getFilename();
    }

    public String getThFileKey(FileInfo fileInfo) {
        if (StrUtil.isBlank(fileInfo.getThFilename())) return null;
        return fileInfo.getBasePath() + fileInfo.getPath() + fileInfo.getThFilename();
    }

    @Override
    public boolean save(FileInfo fileInfo,UploadPretreatment pre) {
        fileInfo.setBasePath(basePath);
        String newFileKey = getFileKey(fileInfo);
        fileInfo.setUrl(domain + newFileKey);
        ObjectMetadata metadata = getObjectMetadata(fileInfo);
        ProgressListener listener = pre.getProgressListener();
        BosClient client = getClient();
        boolean useMultipartUpload = fileInfo.getSize() >= multipartThreshold;
        String uploadId = null;
        try (InputStream in = pre.getFileWrapper().getInputStream()) {
            if (useMultipartUpload) {//分片上传
                InitiateMultipartUploadRequest initiateMultipartUploadRequest = new InitiateMultipartUploadRequest(bucketName,newFileKey);
                initiateMultipartUploadRequest.setObjectMetadata(metadata);
                uploadId = client.initiateMultipartUpload(initiateMultipartUploadRequest).getUploadId();
                List<PartETag> partList = new ArrayList<>();
                int i = 0;
                AtomicLong progressSize = new AtomicLong();
                if (listener != null) listener.start();
                while (true) {
                    byte[] bytes = IoUtil.readBytes(in,multipartPartSize);
                    if (bytes == null || bytes.length == 0) break;
                    UploadPartRequest part = new UploadPartRequest();
                    part.setBucketName(bucketName);
                    part.setKey(newFileKey);
                    part.setUploadId(uploadId);
                    part.setInputStream(new ByteArrayInputStream(bytes));
                    part.setPartSize(bytes.length); // 设置分片大小。除了最后一个分片没有大小限制，其他的分片最小为100 KB。
                    part.setPartNumber(++i); // 设置分片号。每一个上传的分片都有一个分片号，取值范围是1~10000，如果超出此范围，BosClient将返回InvalidArgument错误码。
                    if (listener != null) {
                        part.setProgressCallback(new BosProgressCallback<Object>() {
                            @Override
                            public void onProgress(long currentSize,long totalSize,Object data) {
                                listener.progress(progressSize.get() + currentSize,fileInfo.getSize());
                            }
                        });
                    }
                    partList.add(client.uploadPart(part).getPartETag());
                    progressSize.addAndGet(bytes.length);
                }
                client.completeMultipartUpload(new CompleteMultipartUploadRequest(bucketName,newFileKey,uploadId,partList));
                if (listener != null) listener.finish();
            } else {
                PutObjectRequest request = new PutObjectRequest(bucketName,newFileKey,in,metadata);
                if (listener != null) {
                    listener.start();
                    request.setProgressCallback(new BosProgressCallback<Object>() {
                        @Override
                        public void onProgress(long currentSize,long totalSize,Object data) {
                            listener.progress(currentSize,fileInfo.getSize());
                        }
                    });
                }
                client.putObject(request);
                if (listener != null) listener.finish();
            }
            byte[] thumbnailBytes = pre.getThumbnailBytes();
            if (thumbnailBytes != null) { //上传缩略图
                String newThFileKey = getThFileKey(fileInfo);
                fileInfo.setThUrl(domain + newThFileKey);

                client.putObject(bucketName,newThFileKey,new ByteArrayInputStream(thumbnailBytes),getThObjectMetadata(fileInfo));
            }

            return true;
        } catch (IOException e) {
            if (useMultipartUpload) {
                client.abortMultipartUpload(new AbortMultipartUploadRequest(bucketName,newFileKey,uploadId));
            } else {
                client.deleteObject(bucketName,newFileKey);
            }
            throw new FileStorageRuntimeException("文件上传失败！platform：" + platform + "，filename：" + fileInfo.getOriginalFilename(),e);
        }
    }


    public CannedAccessControlList getAcl(Object acl) {
        if (acl instanceof CannedAccessControlList) {
            return (CannedAccessControlList) acl;
        } else if (acl instanceof String || acl == null) {
            String sAcl = (String) acl;
            if (StrUtil.isEmpty(sAcl)) sAcl = defaultAcl;
            for (CannedAccessControlList item : CannedAccessControlList.values()) {
                if (item.toString().equals(sAcl)) {
                    return item;
                }
            }
        } else {
            throw new FileStorageRuntimeException("不支持的ACL：" + acl);
        }
        return null;
    }


    /**
     * 获取对象的元数据
     */
    public ObjectMetadata getObjectMetadata(FileInfo fileInfo) {
        CannedAccessControlList fileAcl = getAcl(fileInfo.getFileAcl());
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(fileInfo.getSize());
        metadata.setContentType(fileInfo.getContentType());
        if (fileAcl != null) metadata.setxBceAcl(fileAcl.toString());
        metadata.setUserMetadata(fileInfo.getUserMetadata());
        if (CollUtil.isNotEmpty(fileInfo.getMetadata())) {
            CopyOptions copyOptions = CopyOptions.create().ignoreCase().setFieldNameEditor(name -> NamingCase.toCamelCase(name,CharUtil.DASHED));
            BeanUtil.copyProperties(fileInfo.getMetadata(),metadata,copyOptions);
        }
        return metadata;
    }

    /**
     * 获取缩略图对象的元数据
     */
    public ObjectMetadata getThObjectMetadata(FileInfo fileInfo) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(fileInfo.getThSize());
        metadata.setContentType(fileInfo.getThContentType());
        CannedAccessControlList thFileAcl = getAcl(fileInfo.getThFileAcl());
        if (thFileAcl != null) metadata.setxBceAcl(thFileAcl.toString());
        metadata.setUserMetadata(fileInfo.getThUserMetadata());
        if (CollUtil.isNotEmpty(fileInfo.getThMetadata())) {
            CopyOptions copyOptions = CopyOptions.create().ignoreCase().setFieldNameEditor(name -> NamingCase.toCamelCase(name,CharUtil.DASHED));
            BeanUtil.copyProperties(fileInfo.getThMetadata(),metadata,copyOptions);
        }
        return metadata;
    }

    @Override
    public boolean isSupportPresignedUrl() {
        return true;
    }

    @Override
    public String generatePresignedUrl(FileInfo fileInfo,Date expiration) {
        int expires = (int) ((expiration.getTime() - System.currentTimeMillis()) / 1000);
        return getClient().generatePresignedUrl(bucketName,getFileKey(fileInfo),expires).toString();
    }

    @Override
    public String generateThPresignedUrl(FileInfo fileInfo,Date expiration) {
        String key = getThFileKey(fileInfo);
        if (key == null) return null;
        int expires = (int) ((expiration.getTime() - System.currentTimeMillis()) / 1000);
        return getClient().generatePresignedUrl(bucketName,key,expires).toString();
    }

    @Override
    public boolean isSupportAcl() {
        return true;
    }

    @Override
    public boolean setFileAcl(FileInfo fileInfo,Object acl) {
        CannedAccessControlList oAcl = getAcl(acl);
        if (oAcl == null) return false;
        getClient().setObjectAcl(bucketName,getFileKey(fileInfo),oAcl);
        return true;
    }

    @Override
    public boolean setThFileAcl(FileInfo fileInfo,Object acl) {
        CannedAccessControlList oAcl = getAcl(acl);
        if (oAcl == null) return false;
        String key = getThFileKey(fileInfo);
        if (key == null) return false;
        getClient().setObjectAcl(bucketName,key,oAcl);
        return true;
    }

    @Override
    public boolean isSupportMetadata() {
        return true;
    }


    @Override
    public boolean delete(FileInfo fileInfo) {
        BosClient client = getClient();
        if (fileInfo.getThFilename() != null) {   //删除缩略图
            try {
                client.deleteObject(bucketName,getThFileKey(fileInfo));
            } catch (BceServiceException e) {
                if (!"NoSuchKey".equals(e.getErrorCode())) throw e;
            }
        }
        try {
            client.deleteObject(bucketName,getFileKey(fileInfo));
        } catch (BceServiceException e) {
            if (!"NoSuchKey".equals(e.getErrorCode())) throw e;
        }
        return true;
    }


    @Override
    public boolean exists(FileInfo fileInfo) {
        return getClient().doesObjectExist(bucketName,getFileKey(fileInfo));
    }

    @Override
    public void download(FileInfo fileInfo,Consumer<InputStream> consumer) {
        BosObject object = getClient().getObject(bucketName,getFileKey(fileInfo));
        try (InputStream in = object.getObjectContent()) {
            consumer.accept(in);
        } catch (IOException e) {
            throw new FileStorageRuntimeException("文件下载失败！fileInfo：" + fileInfo,e);
        }
    }

    @Override
    public void downloadTh(FileInfo fileInfo,Consumer<InputStream> consumer) {
        if (StrUtil.isBlank(fileInfo.getThFilename())) {
            throw new FileStorageRuntimeException("缩略图文件下载失败，文件不存在！fileInfo：" + fileInfo);
        }
        BosObject object = getClient().getObject(bucketName,getThFileKey(fileInfo));
        try (InputStream in = object.getObjectContent()) {
            consumer.accept(in);
        } catch (IOException e) {
            throw new FileStorageRuntimeException("缩略图文件下载失败！fileInfo：" + fileInfo,e);
        }
    }
}
