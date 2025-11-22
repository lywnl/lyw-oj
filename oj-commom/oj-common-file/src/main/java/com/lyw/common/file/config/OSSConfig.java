package com.lyw.common.file.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class OSSConfig {

    @Autowired
    private OSSProperties prop;

    public OSS ossClient;

    @Bean
    public OSS ossClient() throws ClientException {
        DefaultCredentialProvider credentialsProvider = CredentialsProviderFactory.newDefaultCredentialProvider(
                prop.getAccessKeyId(), prop.getAccessKeySecret());

        // 创建ClientBuilderConfiguration
        ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
        clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);

        // 使用内网endpoint进行上传
        ossClient = OSSClientBuilder.create()
                .endpoint(prop.getEndpoint())
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientBuilderConfiguration)
                .region(prop.getRegion())
                .build();
        return ossClient;
    }

    @PreDestroy
    public void closeOSSClient() {
        ossClient.shutdown();
    }
}
