package edu.iastate.coms.cs472.newspet.utils.dal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import cc.mallet.classify.NaiveBayes;
import cc.mallet.classify.NaiveBayesTrainer;
import cc.mallet.pipe.Pipe;
import edu.iastate.coms.cs472.newspet.utils.ClassifierObjectGroup;
import edu.iastate.coms.cs472.newspet.utils.DocumentConversion;

/**
 * Data access layer for Classifier objects.
 * 
 * @author Michael Fulker
 */
public class DatabaseAccessLayer
{
	static final String LOCK_PREFIX = "classifierUpdate_";
	static final String TABLE_NAME = "Classifier";
	static final String CLASSIFIERGROUP_COLUMN = "serializedClassifier";
	static final String ID_COLUMN = "ID";
	
	/**
	 * <p>Returns a group of objects consisting of a ClassifierTrainer, an instance
	 * Pipe, and an ID.</p>
	 * <p>Typically, access to this method should be done by a synchronization layer.{@link ClassifierDAL#getClassifier(int)} </p>
	 * 
	 * @param classifierId
	 *        the ID of the classifier row to load the trainer for.
	 * @return
	 */
	public static TrainerCheckoutData getTrainerForUpdating(int classifierId)
	{
		try
		{	
			Connection conn = ConnectionConfig.createConnection();
			//make into a transaction
			conn.setAutoCommit(false);
			
			//try to get existing record 
			PreparedStatement getGroup = conn.prepareStatement("SELECT " + CLASSIFIERGROUP_COLUMN + " FROM " + TABLE_NAME + " WHERE " + ID_COLUMN
					+ "=?;");
			getGroup.setInt(1, classifierId);
			ResultSet results = getGroup.executeQuery();
			
			NaiveBayesTrainer trainer;
			Pipe pipe;
			
			//New trainer: we need to create one.
			if(!results.next())
			{
				PreparedStatement insertNew = conn.prepareStatement("INSERT INTO " + TABLE_NAME + " (ID) values (?);");
				insertNew.setInt(1, classifierId);
				insertNew.executeUpdate();
				insertNew.close();
				trainer = new NaiveBayesTrainer();
				pipe = DocumentConversion.createConversionPipes();
			}
			else
			{
				//deserialize
				Blob blob = results.getBlob(CLASSIFIERGROUP_COLUMN);
				if(blob != null)
				{
					ClassifierObjectGroup group = (ClassifierObjectGroup) getObjectFromStream(blob.getBinaryStream());
					trainer = group.getTrainer();
					pipe = group.getPipe();
				}
				else
				//the last execution probably failed
				{
					trainer = new NaiveBayesTrainer();
					pipe = DocumentConversion.createConversionPipes();
				}
			}
		
			results.close();
			getGroup.close();
			if(!conn.getAutoCommit())
				conn.commit();
			
			return new TrainerCheckoutData(trainer, pipe, classifierId);
			
		}
		catch(SQLException e)
		{
			throw new RuntimeException("Couldn't get trainer data for ID " + classifierId, e);
		}
	}
	
	/**
	 * <p>Persists an updated trainer and matching classifier.</p>
	 * <p>Typically, access to this method should be done by a synchronization layer.{@link ClassifierDAL#giveClassifier(int)} </p>
	 * 
	 * 
	 * @param checkin
	 */
	public static void updateTrainerAndClassifier(TrainerCheckoutData checkin)
	{
		try
		{
			Connection conn = ConnectionConfig.createConnection();
			
			Object toPersist = new ClassifierObjectGroup(checkin.getTrainer().getClassifier(), checkin.getTrainer(), checkin.getPipe());
			
			PreparedStatement update = conn.prepareStatement("UPDATE " + TABLE_NAME + " SET " + CLASSIFIERGROUP_COLUMN + "=? WHERE " + ID_COLUMN
					+ "=?;");
			update.setBlob(1, getInputStreamFromObject(toPersist));
			update.setInt(2, checkin.getClasifierID());
			update.executeUpdate();
			update.close();

			if(!conn.getAutoCommit()) conn.commit();
			conn.close();
		}
		catch(SQLException e)
		{
			throw new RuntimeException("Could not persist trainer/classifier for ID " + checkin.getClasifierID(), e);
		}
	}
	
	/**
	 * <p>
	 * Lockless: in the case that the classifier is currently being trained, the
	 * copy existing on the database will be returned.
	 * </p>
	 * <p>
	 * Will return null if the classifier does not exist or has not yet been
	 * trained for the first time.
	 * </p>
	 */
	public static NaiveBayes getClassifier(int classifierID)
	{
		Connection conn = ConnectionConfig.createConnection();
		
		ClassifierObjectGroup group;
		try
		{
			PreparedStatement getGroup = conn.prepareStatement("SELECT " + CLASSIFIERGROUP_COLUMN + " FROM " + TABLE_NAME + " WHERE " + ID_COLUMN
					+ "=?;");
			getGroup.setInt(1, classifierID);
			ResultSet results = getGroup.executeQuery();
			
			//check for nonexistent record
			if(!results.next()) return null;
			
			//get binary data
			Blob blob = results.getBlob(CLASSIFIERGROUP_COLUMN);
			
			//check if first training is incomplete
			if(blob == null) return null;
			
			group = (ClassifierObjectGroup) getObjectFromStream(blob.getBinaryStream());
			
			results.close();
			getGroup.close();
			//don't commit(): autocommit = true
			conn.close();
		}
		catch(SQLException e)
		{
			throw new RuntimeException("Could not retrieve classifier for ID " + classifierID, e);
		}
		
		return group.getClassifier();
	}
	
	/**
	 * Consolidated deserialization method in case we ever want to add
	 * compression/decompression.
	 */
	private static Object getObjectFromStream(InputStream in)
	{
		try
		{
			InputStream decompressor = new java.util.zip.InflaterInputStream(in);
			//ObjectInputStream objReader = new ObjectInputStream(in);
			ObjectInputStream objReader = new ObjectInputStream(decompressor);
			return objReader.readObject();
		}
		catch(IOException e)
		{
			throw new RuntimeException("Could not deserialize", e);
		}
		catch(ClassNotFoundException e)
		{
			throw new RuntimeException("Could not deserialize", e);
		}
	}
	
	private static InputStream getInputStreamFromObject(Object o)
	{
		ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
		try
		{
			OutputStream compressor = new java.util.zip.DeflaterOutputStream(bytesOut);
			ObjectOutputStream objWriter = new ObjectOutputStream(compressor);
			//ObjectOutputStream objWriter = new ObjectOutputStream(bytesOut);
			objWriter.writeObject(o);
			objWriter.close();
			compressor.close();
			bytesOut.close();
		}
		catch(IOException e)
		{
			throw new RuntimeException("Could not serialize", e);
		}
		
		return new ByteArrayInputStream(bytesOut.toByteArray());
	}
}