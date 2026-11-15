package com.chengyinhub.business.service.ai.impl;

import com.chengyinhub.business.service.ai.SpeechToTextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * TEST-only ASR provider used on {@code e2e-test} when DashScope ASR keys are not provisioned.
 * Keeps audio bytes strictly in memory and returns a deterministic user utterance for validated audio containers.
 * Loaded only with profile {@code e2e-test} AND {@code chengyin.e2e.test-providers=true}.
 */
@Service
@Primary
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestSpeechToTextService implements SpeechToTextService
{
    private static final Logger log = LoggerFactory.getLogger(E2eTestSpeechToTextService.class);

    public E2eTestSpeechToTextService()
    {
        log.warn("[e2e-test] TEST-only SpeechToTextService active: in-memory ASR transcription enabled");
    }

    @Override
    public boolean available()
    {
        return true;
    }

    @Override
    public String transcribe(byte[] audio, String format)
    {
        if (audio == null || audio.length == 0 || format == null || format.isBlank())
        {
            return null;
        }
        return "请介绍一下这个站点的特色";
    }
}
