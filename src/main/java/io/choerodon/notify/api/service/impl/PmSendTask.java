package io.choerodon.notify.api.service.impl;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.TemplateException;
import io.choerodon.asgard.schedule.annotation.JobParam;
import io.choerodon.asgard.schedule.annotation.JobTask;
import io.choerodon.core.exception.CommonException;
import io.choerodon.notify.api.pojo.MessageType;
import io.choerodon.notify.api.service.SiteMsgRecordService;
import io.choerodon.notify.domain.SendSetting;
import io.choerodon.notify.domain.Template;
import io.choerodon.notify.infra.feign.UserFeignClient;
import io.choerodon.notify.infra.mapper.SendSettingMapper;
import io.choerodon.notify.infra.mapper.SiteMsgRecordMapper;
import io.choerodon.notify.infra.mapper.TemplateMapper;
import io.choerodon.notify.websocket.send.MessageSender;
import io.choerodon.notify.websocket.send.WebSocketSendPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author dengyouquan
 **/
@Component
public class PmSendTask {
    private static final String CHOERODON_MSG_SIT_MSG = "choerodon:msg:site-msg:";
    private final Logger logger = LoggerFactory.getLogger(PmSendTask.class);
    private static final String MSG_TYPE_PM = "site-msg";
    private final TemplateMapper templateMapper;
    private final TemplateRender templateRender;
    private final MessageSender messageSender;
    private final SiteMsgRecordMapper siteMsgRecordMapper;
    private final SendSettingMapper sendSettingMapper;
    private final SiteMsgRecordService siteMsgRecordService;
    private final ObjectMapper objectMapper;
    private final UserFeignClient userFeignClient;

    public PmSendTask(TemplateMapper templateMapper, TemplateRender templateRender,
                      MessageSender messageSender, UserFeignClient userFeignClient,
                      SiteMsgRecordMapper siteMsgRecordMapper,
                      SiteMsgRecordService siteMsgRecordService,
                      SendSettingMapper sendSettingMapper) {
        this.templateMapper = templateMapper;
        this.templateRender = templateRender;
        this.messageSender = messageSender;
        this.userFeignClient = userFeignClient;
        this.siteMsgRecordMapper = siteMsgRecordMapper;
        this.siteMsgRecordService = siteMsgRecordService;
        this.sendSettingMapper = sendSettingMapper;
        objectMapper = new ObjectMapper();
    }

    @JobTask(maxRetryCount = 1, code = "sendStationLetter", params = {
            @JobParam(name = "code", defaultValue = "addFunction", description = "发送设置编码]"),
            @JobParam(name = "variables", defaultValue = "{'content':'定时任务'}", description = "站内信模板内容的渲染参数")
    }, description = "发送站内信")
    public void sendStationLetter(Map<String, Object> map) {
        String code = Optional.ofNullable((String) map.get("code")).orElseThrow(() -> new CommonException("error.PmSendTask.codeEmpty"));
        String mapJson = Optional.ofNullable((String) map.get("variables")).orElse("");
        Map<String, Object> params = convertJsonToMap(mapJson);
        SendSetting sendSetting = sendSettingMapper.selectOne(new SendSetting(code));
        if (sendSetting == null || sendSetting.getPmTemplateId() == null) {
            logger.info("PmSendTask no sendsetting or sendsetting no opposite station letter template,cann`t send station letter.");
            return;
        }
        Template template = templateMapper.selectByPrimaryKey(sendSetting.getPmTemplateId());
        if (template == null) {
            logger.info("PmSendTask no template,cann`t send station letter.");
            return;
        }
        String pmContent = renderPmTemplate(template, params);
        Long[] ids = userFeignClient.getUserIds().getBody();
        if (ids == null || ids.length == 0) {
            logger.info("PmSendTask current system no user,no send station letter.");
            return;
        }
        sendSocketAndInsertRecord(template, pmContent, ids);
    }

    private Map<String, Object> convertJsonToMap(String mapJson) {
        Map<String, Object> params = new HashMap<>(0);
        if (!StringUtils.isEmpty(mapJson)) {
            try {
                mapJson = mapJson.replaceAll("\'", "\"");
                JavaType javaType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                params = objectMapper.readValue(mapJson, javaType);
            } catch (IOException e) {
                throw new CommonException("error.pmSendTask.paramsJsonNotValid", e);
            }
        }
        return params;
    }

    private void sendSocketAndInsertRecord(Template template, String pmContent, Long[] ids) {
        long startTime = System.currentTimeMillis();
        logger.info("PmSendTask send pm started.");
        //挂起当前事务，先插入记录，再发送websocket,防止用户先收到websocket却没有消息记录
        siteMsgRecordService.insertRecord(template, pmContent, ids);
        logger.info("PmSendTask insert database completed.speed time:{} millisecond", (System.currentTimeMillis() - startTime));
        AtomicInteger count = new AtomicInteger();
        for (Long id : ids) {
            String key = CHOERODON_MSG_SIT_MSG + id;
            messageSender.sendByKey(key, new WebSocketSendPayload<>(MSG_TYPE_PM, key, siteMsgRecordMapper.selectCountOfUnRead(id)));
            count.incrementAndGet();
        }
        logger.info("PmSendTask send websocket completed.count:{}", count);
        long endTime = System.currentTimeMillis();
        logger.info("PmSendTask send pm completed. speed time:{} millisecond", (endTime - startTime));
    }

    private String renderPmTemplate(Template template, Map<String, Object> params) {
        String pm = template.getPmContent();
        try {
            pm = templateRender.renderPmTemplate(template, params);
        } catch (IOException | TemplateException e) {
            throw new CommonException("error.templateRender.renderError", e);
        }
        return pm;
    }
}