/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.text.tokenization.tokenizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.cas.CAS;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.util.CasPool;
import org.cleartk.token.type.Token;
import org.deeplearning4j.text.uima.UimaResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tokenizer based on the passed in analysis engine
 * @author Adam Gibson
 *
 */
public class UimaTokenizer implements Tokenizer {

    private List<String> tokens;
    private int index;
    private static final Logger log = LoggerFactory.getLogger(UimaTokenizer.class);
    private boolean checkForLabel;
    private TokenPreProcess preProcess;


    public UimaTokenizer(String tokens,UimaResource resource,boolean checkForLabel) {
    
        this.checkForLabel = checkForLabel;
        this.tokens = new ArrayList<>();
        try {
            CAS cas = resource.process(tokens);

            Collection<Token> tokenList = JCasUtil.select(cas.getJCas(), Token.class);

            for(Token t : tokenList) {

                if(!checkForLabel || valid(t.getCoveredText()))
                    if(t.getLemma() != null)
                        this.tokens.add(t.getLemma());
                    else if(t.getStem() != null)
                        this.tokens.add(t.getStem());
                    else
                        this.tokens.add(t.getCoveredText());
            }


           resource.release(cas);


        } catch (Exception e) {
        	e.printStackTrace();
            throw new RuntimeException(e);
        }

    }

    private boolean valid(String check) {
        if(check.matches("<[A-Z]+>") || check.matches("</[A-Z]+>"))
            return false;
        return true;
    }



    @Override
    public boolean hasMoreTokens() {
        return index < tokens.size();
    }

    @Override
    public int countTokens() {
        return tokens.size();
    }

    @Override
    public String nextToken() {
        String ret = tokens.get(index);
        index++;
        if(preProcess != null)
            ret = preProcess.preProcess(ret);
        return ret;
    }

    @Override
    public List<String> getTokens() {
        List<String> tokens = new ArrayList<>();
        while(hasMoreTokens()) {
            tokens.add(nextToken());
        }
        return tokens;
    }

	@Override
	public void setTokenPreProcessor(TokenPreProcess tokenPreProcessor) {
       this.preProcess = tokenPreProcessor;
	}




}
