package edu.cmu.minorthird.classify.sequential;

import edu.cmu.minorthird.classify.*;
import edu.cmu.minorthird.classify.algorithms.linear.Hyperplane;
import edu.cmu.minorthird.util.ProgressCounter;
import edu.cmu.minorthird.util.gui.*;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.io.Serializable;
import java.util.Iterator;

/**
 * Generic version of Collin's voted perceptron learner.
 *
 * @author William Cohen
 */

public class GenericCollinsLearner implements BatchSequenceClassifierLearner,SequenceConstants
{
	private OnlineBinaryClassifierLearner innerLearnerPrototype;
	private OnlineBinaryClassifierLearner[] innerLearner;
	private int historySize;
	private int numberOfEpochs;
	private String[] history;

	public GenericCollinsLearner(int historySize,int epochs,OnlineBinaryClassifierLearner innerLearner)
	{
		this.historySize = historySize;
		this.innerLearnerPrototype = innerLearner;
		this.numberOfEpochs = epochs;
		this.history = new String[historySize];
	}

	public SequenceClassifier batchTrain(SequenceDataset dataset)
	{
		ExampleSchema schema = dataset.getSchema();
		try {
			innerLearner = new OnlineBinaryClassifierLearner[ schema.getNumberOfClasses() ];		
			for (int i=0; i<schema.getNumberOfClasses(); i++) {
				innerLearner[i] = (OnlineBinaryClassifierLearner)innerLearnerPrototype.copy();
				innerLearner[i].reset();
			}
		} catch (CloneNotSupportedException ex) {
			throw new IllegalArgumentException("innerLearner must be cloneable");
		}

		ProgressCounter pc =
			new ProgressCounter("training sequential "+innerLearnerPrototype.toString(), 
													"sequence",numberOfEpochs*dataset.numberOfSequences());

		for (int epoch=0; epoch<numberOfEpochs; epoch++) {

			for (Iterator i=dataset.sequenceIterator(); i.hasNext(); ) {

				Example[] sequence = (Example[])i.next();
				Classifier c = new MultiClassClassifier(schema,innerLearner);
				ClassLabel[] viterbi = new BeamSearcher(c,historySize,schema).bestLabelSequence(sequence);
				
				for (int j=0; j<sequence.length; j++) {
					// is the instance at sequence[j] associated with a difference in the sum
					// of feature values over the viterbi sequence and the actual one? 
					boolean differenceAtJ = !viterbi[j].isCorrect( sequence[j].getLabel() );
					for (int k=1; j-k>=0 && !differenceAtJ && k<=historySize; k++) {
						if (!viterbi[j-k].isCorrect( sequence[j-k].getLabel() )) {
							differenceAtJ = true;
						}
					}
					if (differenceAtJ) {
						InstanceFromSequence.fillHistory( history, sequence, j );
						Instance correctXj = new InstanceFromSequence( sequence[j], history );
						int correctClassIndex = schema.getClassIndex( sequence[j].getLabel().bestClassName() );
						innerLearner[correctClassIndex].addExample( new BinaryExample( correctXj, 1.0 ) );
						
						InstanceFromSequence.fillHistory( history, viterbi, j );
						Instance wrongXj = new InstanceFromSequence( sequence[j], history );
						int wrongClassIndex = schema.getClassIndex( viterbi[j].bestClassName() );
						innerLearner[wrongClassIndex].addExample( new BinaryExample( wrongXj, -1.0) );
					}
				} // example sequence j
				pc.progress();
			} // sequence i
		} // epoch
		pc.finished();
			
		// we can use a CMM here, since the classifier is constructed to the same
		// beam search will work
		Classifier c = new MultiClassClassifier(schema,innerLearner);

		return new CMM(c, historySize, schema );
	}

	public static class MultiClassClassifier implements Classifier,Visible,Serializable
	{
		private int serialVersionUID = 1;
		private ExampleSchema schema;
		private BinaryClassifier[] innerClassifier;
		private int numClasses;

		public MultiClassClassifier(ExampleSchema schema,OnlineBinaryClassifierLearner[] innerLearner)
		{
			this.schema = schema;
			this.numClasses = schema.getNumberOfClasses();
			innerClassifier = new BinaryClassifier[ numClasses ];
			for (int i=0; i<numClasses; i++) {
				innerClassifier[i] = innerLearner[i].getBinaryClassifier();
			}
		}
		public ClassLabel classification(Instance instance)
		{
			ClassLabel label = new ClassLabel();
			for (int i=0; i<numClasses; i++) {			
				label.add( schema.getClassName(i), innerClassifier[i].score(instance) );
			}
			return label; 
		}
		public String explain(Instance instance)
		{
			StringBuffer buf = new StringBuffer("");
			for (int i=0; i<numClasses; i++) {			
				buf.append("Classifier for class "+schema.getClassName(i)+":\n");
				buf.append( innerClassifier[i].explain(instance) );
				buf.append("\n");
			}
			return buf.toString();
		}

		public Viewer toGUI()
		{
			Viewer gui = new ComponentViewer() {
					public JComponent componentFor(Object o) {
						MultiClassClassifier c = (MultiClassClassifier)o;
						JPanel main = new JPanel();
						for (int i=0; i<numClasses; i++) {
							JPanel classPanel = new JPanel();
							classPanel.setBorder(new TitledBorder("Class "+c.schema.getClassName(i)));
							Viewer subviewer = new SmartVanillaViewer( c.innerClassifier[i] );
							subviewer.setSuperView( this );
							classPanel.add( subviewer );
							main.add(classPanel);
						}
						return new JScrollPane(main);
					}
				};
			gui.setContent(this);
			return gui;
		}
	}
}
