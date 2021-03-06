package com.googlecode.sardine.ant.command;

import com.googlecode.sardine.ant.Command;

/**
 * A nice ant wrapper around sardine.copy().
 *
 * @author Jon Stevens
 */
public class Copy extends Command
{
	/** */
	private String srcUrl;

	/** */
	private String dstUrl;

	/** */
	@Override
	public void execute() throws Exception
	{
		this.getTask().getSardine().copy(this.srcUrl, this.dstUrl);
	}

	/** */
	@Override
	protected void validateAttributes() throws Exception
	{
		if (this.srcUrl == null || this.dstUrl == null)
			throw new NullPointerException("srcUrl and dstUrl cannot be null");
	}

	/** */
	public void setSrcUrl(String srcUrl)
	{
		this.srcUrl = srcUrl;
	}

	/** */
	public void setDstUrl(String dstUrl)
	{
		this.dstUrl = dstUrl;
	}
}
