package org.brandon.nifi.test;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.exception.ProcessException;

public class TestProcessorOne extends AbstractProcessor
{

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException
    {

    }

    @Override
    protected void init(ProcessorInitializationContext context)
    {

    }
}
